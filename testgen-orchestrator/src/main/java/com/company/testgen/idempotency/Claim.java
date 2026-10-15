package com.company.testgen.idempotency;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Result of claiming an idempotency key (§7.3).
 */
public record Claim(
        ClaimStatus status,
        UUID jobId,
        long leaseEpoch,
        String state,
        String mrUrl,
        String leaseOwner,
        Instant leaseExpiresAt
) {
    public Claim {
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(jobId, "jobId cannot be null");
    }

    public static Claim proceed(UUID jobId, long leaseEpoch) {
        return new Claim(ClaimStatus.PROCEED, jobId, leaseEpoch, null, null, null, null);
    }

    public static Claim resume(UUID jobId, long leaseEpoch, String state) {
        return new Claim(ClaimStatus.RESUME, jobId, leaseEpoch, state, null, null, null);
    }

    public static Claim alreadyComplete(UUID jobId, String mrUrl, String state) {
        return new Claim(ClaimStatus.ALREADY_COMPLETE, jobId, 0L, state, mrUrl, null, null);
    }

    public static Claim alreadyRunning(UUID jobId, String leaseOwner, Instant leaseExpiresAt) {
        return new Claim(ClaimStatus.ALREADY_RUNNING, jobId, 0L, null, null, leaseOwner, leaseExpiresAt);
    }

    public int exitCode() {
        return (status == ClaimStatus.ALREADY_COMPLETE || status == ClaimStatus.ALREADY_RUNNING) ? 20 : 0;
    }

    public boolean isProceed() {
        return status == ClaimStatus.PROCEED;
    }

    public boolean isResume() {
        return status == ClaimStatus.RESUME;
    }

    public boolean isAlreadyComplete() {
        return status == ClaimStatus.ALREADY_COMPLETE;
    }

    public boolean isAlreadyRunning() {
        return status == ClaimStatus.ALREADY_RUNNING;
    }

    public boolean shouldExecute() {
        return status == ClaimStatus.PROCEED || status == ClaimStatus.RESUME;
    }

    public String message() {
        return switch (status) {
            case PROCEED -> "Claimed job " + jobId + " (epoch " + leaseEpoch + ")";
            case RESUME -> "Resumed job " + jobId + " from state " + state + " (epoch " + leaseEpoch + ")";
            case ALREADY_COMPLETE -> "This recording was already processed. MR: " + (mrUrl != null ? mrUrl : "N/A");
            case ALREADY_RUNNING -> "Job " + jobId + " is in flight on " + leaseOwner + " until " + leaseExpiresAt;
        };
    }
}
