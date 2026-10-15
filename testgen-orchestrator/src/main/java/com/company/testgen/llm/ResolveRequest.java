package com.company.testgen.llm;

import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.util.Json;

import java.util.UUID;

/**
 * Request payload for locator resolution (§6.10, §6.13).
 */
public record ResolveRequest(
        UUID jobId,
        Long attemptId,
        int attempt,
        String existingCodeContextJson,
        String safeSnapshotJson,
        String planJson,
        String correctionFeedback
) implements LlmRequest {

    public ResolveRequest {
        if (existingCodeContextJson == null) {
            existingCodeContextJson = "{}";
        }
        if (safeSnapshotJson == null) {
            safeSnapshotJson = "{}";
        }
    }

    public ResolveRequest(
            TestGenerationJob job,
            ParsedTestPlan plan,
            ExistingCodeContext context,
            String correctionFeedback,
            int attempt,
            Long attemptId
    ) {
        this(
                job.id(),
                attemptId,
                attempt,
                context != null ? Json.stringify(context) : "{}",
                plan != null ? Json.stringify(plan.snapshots()) : "{}",
                plan != null ? Json.stringify(plan) : "{}",
                correctionFeedback
        );
    }
}
