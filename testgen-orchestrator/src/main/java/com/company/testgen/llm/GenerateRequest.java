package com.company.testgen.llm;

import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.util.Json;

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

    public GenerateRequest {
        if (codeContextJson == null) {
            codeContextJson = "{}";
        }
    }

    public GenerateRequest(ParsedTestPlan plan, ResolvedLocatorSet locators, ExistingCodeContext context, String baseSha) {
        this(UUID.randomUUID(), null, 0, plan, locators, context != null ? Json.stringify(context) : "{}", baseSha, null);
    }

    public GenerateRequest(ParsedTestPlan plan, ResolvedLocatorSet locators, String codeContextJson, String baseSha) {
        this(UUID.randomUUID(), null, 0, plan, locators, codeContextJson, baseSha, null);
    }

    public GenerateRequest withLintFeedback(String feedback) {
        return new GenerateRequest(jobId, attemptId, attempt + 1, plan, locators, codeContextJson, baseSha, feedback);
    }

    public String existingCodeContextJson() {
        return codeContextJson != null ? codeContextJson : "{}";
    }

    public String feedback() {
        return lintFeedback;
    }
}
