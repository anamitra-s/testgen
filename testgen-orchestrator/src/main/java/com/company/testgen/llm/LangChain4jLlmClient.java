package com.company.testgen.llm;

import com.company.testgen.model.GeneratedFileSet;
import com.company.testgen.model.Phase;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.TokenUsage;
import com.company.testgen.util.Json;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatResponseMetadata;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.Objects;

/**
 * Anthropic-backed implementation of LlmClient using low-level ChatModel API (§6.10).
 * Configured with cacheSystemMessages(true), zero tools with cacheTools(false), and native Structured Outputs.
 */
public class LangChain4jLlmClient implements LlmClient {

    // Pin a DATED snapshot. A floating alias is silent model drift (R-04).
    private final String modelId;
    private final ChatModel model;
    private final TokenBudgetGuard budget;
    private final PromptBundle prompts;
    private final TransportRetryPolicy transport;

    public LangChain4jLlmClient(PipelineProperties props, TokenBudgetGuard budget, PromptBundle prompts) {
        this.modelId = props.llm().modelId();
        this.model = AnthropicChatModel.builder()
                .apiKey(props.llm().apiKey())
                .modelName(modelId)
                .cacheSystemMessages(true)   // cache_control on the last system block
                .cacheTools(false)           // we send ZERO tools - avoids blowing cache block limit
                .temperature(0.0)            // minimize variance; does not guarantee determinism
                .maxTokens(props.llm().maxOutputTokens())
                .maxRetries(0)               // transport retries owned by TransportRetryPolicy
                .timeout(props.llm().timeout())
                .build();
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.prompts = Objects.requireNonNull(prompts, "prompts must not be null");
        this.transport = new TransportRetryPolicy(props.llm().transportRetries());
    }

    /**
     * Testing constructor allowing injection of a custom or mock ChatModel.
     */
    public LangChain4jLlmClient(String modelId,
                                ChatModel model,
                                TokenBudgetGuard budget,
                                PromptBundle prompts,
                                TransportRetryPolicy transport) {
        this.modelId = Objects.requireNonNull(modelId, "modelId must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.prompts = Objects.requireNonNull(prompts, "prompts must not be null");
        this.transport = transport != null ? transport : new TransportRetryPolicy(0);
    }

    @Override
    public ResolvedLocatorSet resolveLocators(ResolveRequest req) {
        // ══════ CACHE-FIRST LAYOUT — the highest-leverage decision in this class ══════
        // langchain4j-anthropic places cache_control on the last SYSTEM message and
        // on tools. It does NOT give an ergonomic breakpoint on a user-message prefix.
        // Therefore everything stable for this job lives in the SYSTEM message.
        // Putting the DOM snapshot in the user message silently disables caching
        // and roughly doubles the cost of a multi-attempt job. (§2.4, §6.10)
        SystemMessage system = SystemMessage.from(String.join("\n\n---\n\n",
                prompts.resolveSystem(),            // stable across ALL jobs
                prompts.codingConventions(),        // stable across ALL jobs
                req.existingCodeContextJson(),      // stable within THIS job
                req.safeSnapshotJson()));           // stable within THIS job  ← the big one

        // Volatile: the only thing that differs between retry attempts.
        String userText = (req.attempt() == 0 || req.correctionFeedback() == null || req.correctionFeedback().isBlank())
                ? prompts.resolveTask(req.planJson())
                : prompts.resolveTask(req.planJson()) + "\n\n" + req.correctionFeedback();
        UserMessage user = UserMessage.from(userText);

        budget.assertHeadroom(req.jobId(), TokenBudgetGuard.estimateTokens(system, user));

        ChatResponse response = transport.execute(() -> model.chat(ChatRequest.builder()
                .messages(system, user)
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(SchemaRegistry.RESOLVED_LOCATOR_SET)
                        .build())
                .build()));

        recordUsage(req, Phase.RESOLVE, response);
        return Json.parse(response.aiMessage().text(), ResolvedLocatorSet.class);
    }

    @Override
    public GeneratedFileSet generateCode(GenerateRequest req) {
        SystemMessage system = SystemMessage.from(String.join("\n\n---\n\n",
                prompts.generateSystem(),
                prompts.codingConventions(),
                req.codeContextJson() != null ? req.codeContextJson() : "{}"));

        String userText = prompts.generateTask(req.plan(), req.locators());
        if (req.lintFeedback() != null && !req.lintFeedback().isBlank()) {
            userText = userText + "\n\n" + req.lintFeedback();
        }
        UserMessage user = UserMessage.from(userText);

        budget.assertHeadroom(req.jobId(), TokenBudgetGuard.estimateTokens(system, user));

        ChatResponse response = transport.execute(() -> model.chat(ChatRequest.builder()
                .messages(system, user)
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(SchemaRegistry.GENERATED_FILE_SET)
                        .build())
                .build()));

        recordUsage(req, Phase.GENERATE, response);
        return Json.parse(response.aiMessage().text(), GeneratedFileSet.class);
    }

    private void recordUsage(LlmRequest req, Phase phase, ChatResponse response) {
        TokenUsage tokenUsage;
        if (response != null && response.metadata() instanceof AnthropicChatResponseMetadata anthropicMeta) {
            AnthropicTokenUsage u = anthropicMeta.tokenUsage();
            if (u != null) {
                tokenUsage = new TokenUsage(
                        u.inputTokenCount() != null ? u.inputTokenCount() : 0,
                        u.outputTokenCount() != null ? u.outputTokenCount() : 0,
                        u.cacheCreationInputTokens() != null ? u.cacheCreationInputTokens() : 0,
                        u.cacheReadInputTokens() != null ? u.cacheReadInputTokens() : 0);
            } else {
                tokenUsage = new TokenUsage(0, 0, 0, 0);
            }
        } else if (response != null && response.metadata() != null && response.metadata().tokenUsage() != null) {
            var u = response.metadata().tokenUsage();
            tokenUsage = new TokenUsage(
                    u.inputTokenCount() != null ? u.inputTokenCount() : 0,
                    u.outputTokenCount() != null ? u.outputTokenCount() : 0,
                    0,
                    0);
        } else {
            tokenUsage = new TokenUsage(0, 0, 0, 0);
        }
        budget.record(req.jobId(), req.attemptId(), phase, modelId, tokenUsage);
    }

    public String modelId() {
        return modelId;
    }

    public ChatModel chatModel() {
        return model;
    }

    public TokenBudgetGuard budgetGuard() {
        return budget;
    }

    public PromptBundle promptBundle() {
        return prompts;
    }
}
