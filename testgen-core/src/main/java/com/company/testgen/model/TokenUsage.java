package com.company.testgen.model;

/**
 * Tracks token usage per LLM invocation including Anthropic prompt cache tokens (§6.2, §6.10).
 */
public record TokenUsage(
        int inputTokens,
        int outputTokens,
        int cacheCreationTokens,
        int cacheReadTokens
) {
    public int totalTokens() {
        return inputTokens + outputTokens + cacheCreationTokens + cacheReadTokens;
    }
}
