package com.company.testgen.idempotency;

/**
 * Possible outcomes of claiming a job through {@link IdempotencyGate} (§7.3).
 */
public enum ClaimStatus {
    PROCEED,
    RESUME,
    ALREADY_COMPLETE,
    ALREADY_RUNNING
}
