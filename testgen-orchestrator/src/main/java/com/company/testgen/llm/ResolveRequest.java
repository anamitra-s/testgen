package com.company.testgen.llm;

import java.util.UUID;

/**
 * Request payload for locator resolution (§6.10).
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
}
