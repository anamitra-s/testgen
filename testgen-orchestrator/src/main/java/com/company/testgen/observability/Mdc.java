package com.company.testgen.observability;

import com.company.testgen.model.Phase;
import com.company.testgen.model.TestGenerationJob;
import org.slf4j.MDC;

/**
 * Manages Mapped Diagnostic Context (MDC) for structured JSON logging (§9.3, FR-33).
 * Captures jobId, idempotencyKey, targetArea, requester, leaseEpoch, promptBundleHash, and modelId.
 * AutoCloseable to ensure context is cleaned up cleanly.
 */
public final class Mdc implements AutoCloseable {

    public static Mdc forJob(TestGenerationJob job) {
        if (job != null) {
            if (job.id() != null) MDC.put("jobId", job.id().toString());
            if (job.idempotencyKey() != null) MDC.put("idempotencyKey", job.idempotencyKey());
            if (job.targetArea() != null) MDC.put("targetArea", job.targetArea());
            if (job.requester() != null) MDC.put("requester", job.requester());
            MDC.put("leaseEpoch", String.valueOf(job.leaseEpoch()));
            if (job.promptBundleHash() != null) MDC.put("promptBundleHash", job.promptBundleHash());
            if (job.modelId() != null) MDC.put("modelId", job.modelId());
        }
        String buildUrl = System.getenv("BUILD_URL");
        if (buildUrl != null && !buildUrl.isBlank()) {
            MDC.put("buildUrl", buildUrl);
        }
        return new Mdc();
    }

    public static void phase(String phase, int attempt) {
        if (phase != null) {
            MDC.put("phase", phase);
        }
        MDC.put("attempt", String.valueOf(attempt));
    }

    public static void phase(Phase phase, int attempt) {
        if (phase != null) {
            MDC.put("phase", phase.name());
        }
        MDC.put("attempt", String.valueOf(attempt));
    }

    public static void clear() {
        MDC.clear();
    }

    @Override
    public void close() {
        MDC.clear();
    }
}
