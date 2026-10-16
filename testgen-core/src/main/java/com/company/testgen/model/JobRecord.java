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
        String buildUrl,
        String redactionReport,
        Integer mrIid,
        String mrUrl,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
    /**
     * Backward-compatible constructor omitting buildUrl.
     */
    public JobRecord(
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
        this(id, idempotencyKey, targetArea, testDescription, requester, recordingBranch, state,
                confidenceTier, failureReason, promptBundleHash, modelId, orchestratorVersion,
                recorderVersion, baseCommitSha, leaseOwner, leaseEpoch, leaseExpiresAt,
                null, redactionReport, mrIid, mrUrl, createdAt, updatedAt, completedAt);
    }

    /**
     * Factory method for creating a freshly claimed job record with explicit buildUrl.
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
            Instant leaseExpiresAt,
            String buildUrl
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
                buildUrl,
                null,
                null,
                null,
                now,
                now,
                null
        );
    }

    /**
     * Factory method for creating a freshly claimed job record defaulting buildUrl from env.
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
        return newClaimed(
                id, idempotencyKey, targetArea, testDescription, requester, recordingBranch,
                promptBundleHash, modelId, orchestratorVersion, recorderVersion, baseCommitSha,
                leaseOwner, leaseEpoch, leaseExpiresAt, System.getenv("BUILD_URL")
        );
    }

    /**
     * Returns the Jenkins build URL for this job or a synthesized fallback.
     */
    public String jenkinsBuildUrl() {
        if (buildUrl != null && !buildUrl.isBlank()) {
            return buildUrl;
        }
        String base = System.getenv("BUILD_URL");
        return base != null && !base.isBlank() ? base : "https://jenkins.example.com/job/testgen-pipeline/" + id;
    }
}
