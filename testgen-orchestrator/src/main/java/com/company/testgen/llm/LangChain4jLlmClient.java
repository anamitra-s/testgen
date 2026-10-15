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
import java.util.UUID;

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

    public LangChain4jLlmClient(com.company.testgen.config.PipelineProperties props, TokenBudgetGuard budget, PromptBundle prompts) {
        this.modelId = props.llm().modelId();
        this.model = AnthropicChatModel.builder()
                .apiKey(props.llm().apiKey() != null ? props.llm().apiKey() : "dummy-key")
                .modelName(modelId)
                .cacheSystemMessages(props.llm().cacheSystemMessages())
                .cacheTools(props.llm().cacheTools())
                .temperature(props.llm().temperature())
                .maxTokens(props.llm().maxOutputTokens())
                .maxRetries(0)
                .timeout(props.llm().timeout())
                .build();
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.prompts = Objects.requireNonNull(prompts, "prompts must not be null");
        this.transport = new TransportRetryPolicy(props.llm().transportRetries(), props.llm().transportBackoffBase());
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
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    @Override
    public ResolvedLocatorSet resolveLocators(ResolveRequest req) {
        Objects.requireNonNull(req, "request must not be null");
        UUID jobId = req.jobId() != null ? req.jobId() : UUID.randomUUID();

        // ═══ CACHE-FIRST LAYOUT — the highest-leverage decision in this class ═══
        // Everything stable for this job lives in the SYSTEM message (§2.4, §6.10).
        SystemMessage system = SystemMessage.from(String.join("\n\n---\n\n",
                prompts.resolveSystem(),            // stable across ALL jobs
                prompts.codingConventions(),        // stable across ALL jobs
                req.existingCodeContextJson(),      // stable within THIS job
                req.safeSnapshotJson()));           // stable within THIS job

        // Volatile: the only thing that differs between retry attempts.
        UserMessage user = UserMessage.from(req.attempt() == 0
                ? prompts.resolveTask(req.planJson())
                : prompts.resolveTask(req.planJson()) + "\n\n" + req.correctionFeedback());

        budget.assertHeadroom(jobId, estimateTokens(system, user));

        ChatResponse response = transport.execute(() -> model.chat(ChatRequest.builder()
                .messages(system, user)
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(SchemaRegistry.RESOLVED_LOCATOR_SET)
                        .build())
                .build()));

        recordUsage(req, Phase.RESOLVE, response);
        String rawText = response.aiMessage() != null ? response.aiMessage().text() : "{}";
        return Json.parse(stripCodeFences(rawText), ResolvedLocatorSet.class);
    }

    @Override
    public GeneratedFileSet generateCode(GenerateRequest req) {
        Objects.requireNonNull(req, "request must not be null");
        UUID jobId = req.jobId() != null ? req.jobId() : UUID.randomUUID();

        // ═══ CACHE-FIRST LAYOUT — matching resolveLocators (§2.4, §6.10, §6.12) ═══
        // Stable system message cached across attempts
        SystemMessage system = SystemMessage.from(String.join("\n\n---\n\n",
                prompts.generateSystem(),           // stable across ALL jobs
                prompts.codingConventions(),        // stable across ALL jobs
                req.existingCodeContextJson()));    // stable within THIS job

        // Volatile user message: task instructions plus lint feedback if regeneration attempt
        String taskContent = prompts.generateTask(req.plan(), req.locators());
        if (req.attempt() > 0 && req.feedback() != null && !req.feedback().isBlank()) {
            taskContent += "\n\nLint Feedback:\n" + req.feedback();
        } else if (req.feedback() != null && !req.feedback().isBlank()) {
            taskContent += "\n\n" + req.feedback();
        }
        UserMessage user = UserMessage.from(taskContent);

        budget.assertHeadroom(jobId, estimateTokens(system, user));

        ChatResponse response = transport.execute(() -> model.chat(ChatRequest.builder()
                .messages(system, user)
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(SchemaRegistry.GENERATED_FILE_SET)
                        .build())
                .build()));

        recordUsage(req, Phase.GENERATE, response);
        String rawText = response.aiMessage() != null ? response.aiMessage().text() : "{}";
        return Json.parse(stripCodeFences(rawText), GeneratedFileSet.class);
    }

    private static String stripCodeFences(String raw) {
        if (raw == null) {
            return "{}";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```json") && trimmed.endsWith("```")) {
            return trimmed.substring(7, trimmed.length() - 3).trim();
        } else if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            return trimmed.substring(3, trimmed.length() - 3).trim();
        }
        return trimmed;
    }

    private void recordUsage(LlmRequest req, Phase phase, ChatResponse response) {
        UUID jobId = (req != null && req.jobId() != null) ? req.jobId() : UUID.randomUUID();
        Long attemptId = req != null ? req.attemptId() : null;
        if (response.metadata() instanceof AnthropicChatResponseMetadata anthropicMeta
                && anthropicMeta.tokenUsage() instanceof AnthropicTokenUsage u) {
            budget.record(jobId, attemptId, phase, modelId, new TokenUsage(
                    u.inputTokenCount(),
                    u.outputTokenCount(),
                    u.cacheCreationInputTokens() != null ? u.cacheCreationInputTokens() : 0,
                    u.cacheReadInputTokens() != null ? u.cacheReadInputTokens() : 0
            ));
        } else if (response.metadata() != null && response.metadata().tokenUsage() != null) {
            var standardUsage = response.metadata().tokenUsage();
            budget.record(jobId, attemptId, phase, modelId, new TokenUsage(
                    standardUsage.inputTokenCount(),
                    standardUsage.outputTokenCount(),
                    0,
                    0
            ));
        }
    }

    /**
     * Estimates tokens via character count / 3.6 heuristic (§6.10).
     */
    int estimateTokens(SystemMessage system, UserMessage user) {
        int chars = (system != null ? system.text().length() : 0)
                + (user != null ? user.singleText().length() : 0);
        return (int) Math.ceil(chars / 3.6);
    }
}
