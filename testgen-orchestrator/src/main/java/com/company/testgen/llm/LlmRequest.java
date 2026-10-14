package com.company.testgen.llm;

import java.util.UUID;

/**
 * Common metadata interface for LLM requests (§6.10).
 */
public interface LlmRequest {
    UUID jobId();
    Long attemptId();
    int attempt();
}
