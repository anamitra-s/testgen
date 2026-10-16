package com.company.testgen.llm;

import com.company.testgen.model.Phase;
import com.company.testgen.model.TokenUsage;
import com.company.testgen.persistence.CostEventRepository;
import com.company.testgen.persistence.InMemoryCostEventRepository;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBudgetGuardTest {

    private CostEventRepository repo;
    private PriceBook priceBook;
    private TokenBudgetGuard budgetGuard;
    private UUID jobId;

    @BeforeEach
    void setUp() {
        repo = new InMemoryCostEventRepository();
        priceBook = new PriceBook();
        // 10,000 max tokens, $1.00 USD cap
        budgetGuard = new TokenBudgetGuard(10_000, new BigDecimal("1.00"), repo, priceBook);
        jobId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Pessimistic estimation computes ceil(chars / 3.6)")
    void testTokenEstimation() {
        // 36 chars / 3.6 = 10 tokens
        String text36 = "123456789012345678901234567890123456";
        assertThat(TokenBudgetGuard.estimateTokens(text36)).isEqualTo(10);

        // 37 chars / 3.6 = 10.27 -> 11 tokens
        String text37 = text36 + "A";
        assertThat(TokenBudgetGuard.estimateTokens(text37)).isEqualTo(11);

        // ChatMessages estimation
        SystemMessage system = SystemMessage.from(text36);
        UserMessage user = UserMessage.from(text37);
        // (36 + 37) / 3.6 = 73 / 3.6 = 20.27 -> 21
        assertThat(TokenBudgetGuard.estimateTokens(system, user)).isEqualTo(21);
    }

    @Test
    @DisplayName("assertHeadroom passes when headroom exists")
    void testAssertHeadroomPasses() {
        budgetGuard.assertHeadroom(jobId, 5_000);
    }

    @Test
    @DisplayName("assertHeadroom circuit-breaks when estimated tokens exceed limit")
    void testAssertHeadroomBreachesTokenLimit() {
        // Initial spend 6,000 tokens
        budgetGuard.record(jobId, 1L, Phase.RESOLVE, "claude-sonnet-5", new TokenUsage(4000, 2000, 0, 0));

        // 6000 + 4001 = 10001 > 10000 limit -> throws
        assertThatThrownBy(() -> budgetGuard.assertHeadroom(jobId, 4_001))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("Job token budget exceeded")
                .satisfies(ex -> {
                    BudgetExceededException be = (BudgetExceededException) ex;
                    assertThat(be.reason()).isEqualTo("token");
                    assertThat(be.currentTokens()).isEqualTo(6000);
                    assertThat(be.estimatedTokens()).isEqualTo(4001);
                    assertThat(be.maxTokens()).isEqualTo(10000);
                });
    }

    @Test
    @DisplayName("assertHeadroom hard circuit-breaks when USD cap is breached")
    void testAssertHeadroomBreachesUsdCap() {
        // Record spend that costs >= $1.00 USD
        // 100,000 output tokens at $15/Mtok = $1.50
        budgetGuard.record(jobId, 1L, Phase.GENERATE, "claude-sonnet-5", new TokenUsage(100, 100_000, 0, 0));

        JobSpend spend = repo.spendFor(jobId);
        assertThat(spend.usd()).isGreaterThanOrEqualTo(new BigDecimal("1.00"));

        // Pre-flight check should immediately refuse any further calls
        assertThatThrownBy(() -> budgetGuard.assertHeadroom(jobId, 10))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("Job USD budget exceeded")
                .satisfies(ex -> {
                    BudgetExceededException be = (BudgetExceededException) ex;
                    assertThat(be.reason()).isEqualTo("usd");
                    assertThat(be.currentUsd()).isGreaterThanOrEqualTo(new BigDecimal("1.00"));
                });
    }

    @Test
    @DisplayName("PriceBook correctly calculates input, output, cache creation, and cache read costs")
    void testPriceBookCalculations() {
        // Claude Sonnet pricing:
        // Input: $3.00 / Mtok
        // Output: $15.00 / Mtok
        // Cache creation: $3.75 / Mtok
        // Cache read: $0.30 / Mtok
        TokenUsage usage = new TokenUsage(1_000_000, 1_000_000, 1_000_000, 1_000_000);
        BigDecimal cost = priceBook.cost("claude-sonnet-5", usage);

        // Expected: 3.00 + 15.00 + 3.75 + 0.30 = 22.050000
        assertThat(cost).isEqualByComparingTo(new BigDecimal("22.050000"));
    }

    @Test
    @DisplayName("record inserts CostEvent into repository with accurate token breakdown and cost")
    void testRecordPersistsCostEvent() {
        TokenUsage usage = new TokenUsage(10_000, 2_000, 50_000, 20_000);
        budgetGuard.record(jobId, 2L, Phase.RESOLVE, "claude-sonnet-5", usage);

        List<CostEvent> events = repo.findByJobId(jobId);
        assertThat(events).hasSize(1);
        CostEvent event = events.getFirst();
        assertThat(event.jobId()).isEqualTo(jobId);
        assertThat(event.attemptId()).isEqualTo(2L);
        assertThat(event.phase()).isEqualTo(Phase.RESOLVE);
        assertThat(event.tokenUsage()).isEqualTo(usage);
        assertThat(event.estimatedCostUsd()).isPositive();

        JobSpend spend = repo.spendFor(jobId);
        assertThat(spend.totalTokens()).isEqualTo(usage.totalTokens());
        assertThat(spend.usd()).isEqualTo(event.estimatedCostUsd());
    }
}
