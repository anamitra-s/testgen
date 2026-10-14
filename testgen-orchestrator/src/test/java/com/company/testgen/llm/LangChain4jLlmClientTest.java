package com.company.testgen.llm;

import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.Phase;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.util.Json;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatResponseMetadata;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChain4jLlmClientTest {

    private InMemoryCostEventRepository repo;
    private PriceBook priceBook;
    private TokenBudgetGuard budgetGuard;
    private PromptBundle promptBundle;
    private UUID jobId;

    @BeforeEach
    void setUp() {
        repo = new InMemoryCostEventRepository();
        priceBook = new PriceBook();
        budgetGuard = new TokenBudgetGuard(100_000, new BigDecimal("10.00"), repo, priceBook);
        promptBundle = new PromptBundle();
        jobId = UUID.randomUUID();
    }

    @Test
    @DisplayName("resolveLocators builds SystemMessage with safeSnapshotJson and volatile instructions in UserMessage")
    void testResolveLocatorsMessageConstructionAndCaching() {
        AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();

        ResolvedLocatorSet expectedResponse = new ResolvedLocatorSet(List.of(
                new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "submit-btn", null, "page.getByTestId(\"submit-btn\")")
        ));
        String responseJson = Json.stringify(expectedResponse);

        AnthropicTokenUsage anthropicTokens = AnthropicTokenUsage.builder()
                .inputTokenCount(1500)
                .outputTokenCount(80)
                .cacheCreationInputTokens(1200)
                .cacheReadInputTokens(300)
                .build();

        AnthropicChatResponseMetadata metadata = AnthropicChatResponseMetadata.builder()
                .tokenUsage(anthropicTokens)
                .build();

        ChatModel fakeModel = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest chatRequest) {
                capturedRequest.set(chatRequest);
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(responseJson))
                        .metadata(metadata)
                        .build();
            }
        };

        LangChain4jLlmClient client = new LangChain4jLlmClient(
                "claude-sonnet-5",
                fakeModel,
                budgetGuard,
                promptBundle,
                new TransportRetryPolicy(0)
        );

        String safeSnapshotJson = "{\"dom\": \"<button data-testid='submit-btn'>Submit</button>\"}";
        String existingContextJson = "{\"pages\": [\"LoginPage\"]}";
        String planJson = "{\"action\": \"click\", \"elementRef\": \"e1\"}";

        ResolveRequest request = new ResolveRequest(
                jobId,
                1L,
                0,
                existingContextJson,
                safeSnapshotJson,
                planJson,
                null
        );

        ResolvedLocatorSet result = client.resolveLocators(request);

        assertThat(result).isNotNull();
        assertThat(result.locators()).hasSize(1);
        assertThat(result.locators().getFirst().strategy()).isEqualTo(LocatorStrategy.TEST_ID);

        // Verify request payload
        ChatRequest chatReq = capturedRequest.get();
        assertThat(chatReq).isNotNull();
        assertThat(chatReq.messages()).hasSize(2);

        // SystemMessage MUST contain safeSnapshotJson to force Anthropic prompt caching
        ChatMessage sysMsg = chatReq.messages().getFirst();
        assertThat(sysMsg).isInstanceOf(SystemMessage.class);
        String sysText = ((SystemMessage) sysMsg).text();
        assertThat(sysText).contains(safeSnapshotJson);
        assertThat(sysText).contains(existingContextJson);
        assertThat(sysText).contains(promptBundle.resolveSystem());
        assertThat(sysText).contains(promptBundle.codingConventions());

        // UserMessage must contain task instructions
        ChatMessage userMsg = chatReq.messages().get(1);
        assertThat(userMsg).isInstanceOf(UserMessage.class);
        String userText = ((UserMessage) userMsg).singleText();
        assertThat(userText).contains(planJson);

        // Structured Outputs configuration check
        assertThat(chatReq.responseFormat()).isNotNull();
        assertThat(chatReq.responseFormat().type()).isEqualTo(ResponseFormatType.JSON);
        assertThat(chatReq.responseFormat().jsonSchema()).isEqualTo(SchemaRegistry.RESOLVED_LOCATOR_SET);

        // Verify AnthropicTokenUsage was correctly extracted and recorded into TokenBudgetGuard
        List<CostEvent> events = repo.findByJobId(jobId);
        assertThat(events).hasSize(1);
        CostEvent event = events.getFirst();
        assertThat(event.phase()).isEqualTo(Phase.RESOLVE);
        assertThat(event.tokenUsage().inputTokens()).isEqualTo(1500);
        assertThat(event.tokenUsage().outputTokens()).isEqualTo(80);
        assertThat(event.tokenUsage().cacheCreationTokens()).isEqualTo(1200);
        assertThat(event.tokenUsage().cacheReadTokens()).isEqualTo(300);
    }

    @Test
    @DisplayName("resolveLocators retry (attempt > 0) incorporates correctionFeedback in UserMessage")
    void testResolveLocatorsRetryWithFeedback() {
        AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();

        ResolvedLocatorSet expectedResponse = new ResolvedLocatorSet(List.of(
                new ResolvedLocator("e1", LocatorStrategy.ROLE_NAME, "Submit", "BUTTON",
                        "page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(\"Submit\").setExact(true))")
        ));

        ChatModel fakeModel = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest chatRequest) {
                capturedRequest.set(chatRequest);
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(Json.stringify(expectedResponse)))
                        .metadata(AnthropicChatResponseMetadata.builder().build())
                        .build();
            }
        };

        LangChain4jLlmClient client = new LangChain4jLlmClient(
                "claude-sonnet-5",
                fakeModel,
                budgetGuard,
                promptBundle,
                new TransportRetryPolicy(0)
        );

        ResolveRequest request = new ResolveRequest(
                jobId,
                2L,
                1,
                "{}",
                "<snapshot></snapshot>",
                "{\"step\": 1}",
                "Previous locator e1 failed L1 strictness check: matched 2 elements."
        );

        client.resolveLocators(request);

        UserMessage userMsg = (UserMessage) capturedRequest.get().messages().get(1);
        assertThat(userMsg.singleText()).contains("Previous locator e1 failed L1 strictness check");
    }

    @Test
    @DisplayName("Pre-flight headroom check prevents LLM invocation if budget is exhausted")
    void testPreFlightBudgetCheckPreventsCall() {
        AtomicInteger callCount = new AtomicInteger(0);

        ChatModel fakeModel = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest chatRequest) {
                callCount.incrementAndGet();
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("{}"))
                        .build();
            }
        };

        // Budget guard with only 100 max tokens
        TokenBudgetGuard strictGuard = new TokenBudgetGuard(100, new BigDecimal("1.00"), repo, priceBook);

        LangChain4jLlmClient client = new LangChain4jLlmClient(
                "claude-sonnet-5",
                fakeModel,
                strictGuard,
                promptBundle,
                new TransportRetryPolicy(0)
        );

        ResolveRequest request = new ResolveRequest(
                jobId,
                1L,
                0,
                "{}",
                "<snapshot>very long snapshot text exceeding 100 tokens</snapshot>",
                "plan",
                null
        );

        assertThatThrownBy(() -> client.resolveLocators(request))
                .isInstanceOf(BudgetExceededException.class);

        assertThat(callCount.get()).isZero();
    }

    @Test
    @DisplayName("TransportRetryPolicy retries on transient errors and succeeds")
    void testTransportRetryPolicyRecovers() {
        AtomicInteger attempts = new AtomicInteger(0);

        ResolvedLocatorSet expectedResponse = new ResolvedLocatorSet(List.of());

        ChatModel fakeModel = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest chatRequest) {
                if (attempts.incrementAndGet() == 1) {
                    throw new RuntimeException("429 Too Many Requests: Rate limit exceeded");
                }
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(Json.stringify(expectedResponse)))
                        .metadata(AnthropicChatResponseMetadata.builder().build())
                        .build();
            }
        };

        LangChain4jLlmClient client = new LangChain4jLlmClient(
                "claude-sonnet-5",
                fakeModel,
                budgetGuard,
                promptBundle,
                new TransportRetryPolicy(2)
        );

        ResolveRequest request = new ResolveRequest(jobId, 1L, 0, "{}", "{}", "{}", null);
        ResolvedLocatorSet result = client.resolveLocators(request);

        assertThat(result).isNotNull();
        assertThat(attempts.get()).isEqualTo(2);
    }
}
