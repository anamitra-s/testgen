package com.company.testgen.idempotency;

/**
 * Types of external side-effects managed by the effect ledger (§6.1, §6.14, §7.5).
 */
public enum EffectType {
    BRANCH,
    COMMIT,
    MERGE_REQUEST,
    SLACK
}
