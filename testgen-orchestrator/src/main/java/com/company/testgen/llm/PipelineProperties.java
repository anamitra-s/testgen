package com.company.testgen.llm;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Pipeline configuration properties for LLM integration and budget enforcement (§6.10).
 */
public record PipelineProperties(
        LlmProperties llm,
        BudgetProperties budget
) {
    public record LlmProperties(
            String apiKey,
            String modelId,
            int maxOutputTokens,
            Duration timeout,
            int transportRetries
    ) {
        public static final String DEFAULT_MODEL_ID = "claude-sonnet-5";
        public static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;
        public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
        public static final int DEFAULT_TRANSPORT_RETRIES = 3;

        public static LlmProperties defaults(String apiKey) {
            return new LlmProperties(apiKey, DEFAULT_MODEL_ID, DEFAULT_MAX_OUTPUT_TOKENS, DEFAULT_TIMEOUT, DEFAULT_TRANSPORT_RETRIES);
        }
    }

    public record BudgetProperties(
            int maxTokensPerJob,
            BigDecimal maxUsdPerJob
    ) {
        public static final int DEFAULT_MAX_TOKENS = 500_000;
        public static final BigDecimal DEFAULT_MAX_USD = new BigDecimal("5.00");

        public static BudgetProperties defaults() {
            return new BudgetProperties(DEFAULT_MAX_TOKENS, DEFAULT_MAX_USD);
        }
    }

    public static PipelineProperties defaults(String apiKey) {
        return new PipelineProperties(LlmProperties.defaults(apiKey), BudgetProperties.defaults());
    }
}
