package com.company.testgen.llm;

import com.company.testgen.model.Phase;
import com.company.testgen.model.TokenUsage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Pre-flights token estimates and enforces hard circuit-breaks based on token limits
 * and USD caps (§6.10, NFR-23).
 */
public class TokenBudgetGuard {

    private static final double CHARS_PER_TOKEN = 3.6;

    private final int maxTokensPerJob;
    private final BigDecimal maxUsdPerJob;
    private final CostEventRepository repo;
    private final PriceBook prices;

    public TokenBudgetGuard(int maxTokensPerJob, BigDecimal maxUsdPerJob, CostEventRepository repo, PriceBook prices) {
        this.maxTokensPerJob = maxTokensPerJob;
        this.maxUsdPerJob = Objects.requireNonNull(maxUsdPerJob, "maxUsdPerJob must not be null");
        this.repo = Objects.requireNonNull(repo, "repo must not be null");
        this.prices = Objects.requireNonNull(prices, "prices must not be null");
    }

    /**
     * Pessimistic token estimation based on chars / 3.6 across text contents.
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /**
     * Pessimistic token estimation across multiple strings.
     */
    public static int estimateTokens(String... texts) {
        if (texts == null || texts.length == 0) {
            return 0;
        }
        int totalChars = 0;
        for (String t : texts) {
            if (t != null) {
                totalChars += t.length();
            }
        }
        return (int) Math.ceil(totalChars / CHARS_PER_TOKEN);
    }

    /**
     * Pessimistic token estimation across assembled ChatMessages.
     */
    public static int estimateTokens(ChatMessage... messages) {
        if (messages == null || messages.length == 0) {
            return 0;
        }
        int totalChars = 0;
        for (ChatMessage msg : messages) {
            String text = extractText(msg);
            if (text != null) {
                totalChars += text.length();
            }
        }
        return (int) Math.ceil(totalChars / CHARS_PER_TOKEN);
    }

    public static String extractText(ChatMessage msg) {
        if (msg instanceof SystemMessage sm) {
            return sm.text();
        } else if (msg instanceof UserMessage um) {
            return um.singleText();
        } else if (msg instanceof AiMessage am) {
            return am.text();
        }
        return "";
    }

    /**
     * Verifies headroom before making an LLM call. Throws BudgetExceededException
     * if the hard USD cap or token limit is breached.
     */
    public void assertHeadroom(UUID jobId, int estimatedTokens) {
        JobSpend spend = repo.spendFor(jobId);
        if (spend.usd().compareTo(maxUsdPerJob) >= 0) {
            throw new BudgetExceededException("usd", spend.usd(), maxUsdPerJob);
        }
        if (spend.totalTokens() + estimatedTokens > maxTokensPerJob) {
            throw new BudgetExceededException("token", spend.totalTokens(), estimatedTokens, maxTokensPerJob);
        }
    }

    /**
     * Records token usage and calculated USD cost to the audit repository.
     */
    public void record(UUID jobId, Long attemptId, Phase phase, String modelId, TokenUsage u) {
        BigDecimal cost = prices.cost(modelId, u);
        repo.insert(new CostEvent(jobId, attemptId, phase, modelId, u, cost));
    }

    public int maxTokensPerJob() {
        return maxTokensPerJob;
    }

    public BigDecimal maxUsdPerJob() {
        return maxUsdPerJob;
    }

    public CostEventRepository repository() {
        return repo;
    }

    public PriceBook prices() {
        return prices;
    }
}
