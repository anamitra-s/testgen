package com.company.testgen.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain representation of a persisted row in the {@code jobs} table (§6.14, §7.2-7.4).
 */
public record JobRecord(
        UUID id,
        String idempotencyKey,
        String targetArea,
        String testDescription,
        String requester,
        String recordingBranch,
        String state,
        String confidenceTier,
        String failureReason,
        String promptBundleHash,
        String modelId,
        String orchestratorVersion,
        String recorderVersion,
        String baseCommitSha,
        String leaseOwner,
        long leaseEpoch,
        Instant leaseExpiresAt,
        String redactionReport,
        Integer mrIid,
        String mrUrl,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
    /**
     * Factory method for creating a freshly claimed job record.
     */
    public static JobRecord newClaimed(
            UUID id,
            String idempotencyKey,
            String targetArea,
            String testDescription,
            String requester,
            String recordingBranch,
            String promptBundleHash,
            String modelId,
            String orchestratorVersion,
            String recorderVersion,
            String baseCommitSha,
            String leaseOwner,
            long leaseEpoch,
            Instant leaseExpiresAt
    ) {
        Instant now = Instant.now();
        return new JobRecord(
                id,
                idempotencyKey,
                targetArea,
                testDescription,
                requester,
                recordingBranch,
                "CLAIMED",
                null,
                null,
                promptBundleHash,
                modelId,
                orchestratorVersion,
                recorderVersion,
                baseCommitSha,
                leaseOwner,
                leaseEpoch,
                leaseExpiresAt,
                null,
                null,
                null,
                now,
                now,
                null
        );
    }
}
