package com.company.testgen.observability;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * Structured build artifact emitted as job-summary.json upon terminal state (§9.3, FR-35).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobSummary(
        String jobId,
        String idempotencyKey,
        String state,
        String confidenceTier,
        String targetArea,
        int attempts,
        boolean escalated,
        String failureReason,
        List<LocatorSummary> locators,
        VerificationSummary verification,
        CostSummary cost,
        ProvenanceSummary provenance,
        String mrUrl,
        long durationMs
) {
    public record LocatorSummary(
            String elementRefId,
            String strategy,
            String value,
            String l0,
            String l1
    ) {}

    public record VerificationSummary(
            String outcome,
            Long durationMs
    ) {}

    public record CostSummary(
            int inputTokens,
            int cacheReadTokens,
            int outputTokens,
            BigDecimal usd
    ) {}

    public record ProvenanceSummary(
            String modelId,
            String promptBundleHash,
            String orchestratorVersion,
            String baseCommitSha
    ) {}
}
