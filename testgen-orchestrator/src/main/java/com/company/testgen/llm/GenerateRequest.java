package com.company.testgen.llm;

import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.ResolvedLocatorSet;

import java.util.UUID;

/**
 * Request payload for test code generation (§6.10, §6.12).
 */
public record GenerateRequest(
        UUID jobId,
        Long attemptId,
        int attempt,
        ParsedTestPlan plan,
        ResolvedLocatorSet locators,
        String codeContextJson,
        String baseSha,
        String lintFeedback
) implements LlmRequest {

    public GenerateRequest(ParsedTestPlan plan, ResolvedLocatorSet locators, String codeContextJson, String baseSha) {
        this(UUID.randomUUID(), null, 0, plan, locators, codeContextJson, baseSha, null);
    }

    public GenerateRequest withLintFeedback(String feedback) {
        return new GenerateRequest(jobId, attemptId, attempt + 1, plan, locators, codeContextJson, baseSha, feedback);
    }
}
