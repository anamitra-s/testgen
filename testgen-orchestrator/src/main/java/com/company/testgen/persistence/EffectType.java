package com.company.testgen.persistence;

/**
 * Types of external side-effects managed by the effect ledger (§6.14, §7.5).
 * @deprecated Use {@link com.company.testgen.idempotency.EffectType} instead (§6.1).
 */
@Deprecated
public enum EffectType {
    BRANCH,
    COMMIT,
    MERGE_REQUEST,
    SLACK;

    public com.company.testgen.idempotency.EffectType toIdempotencyType() {
        return com.company.testgen.idempotency.EffectType.valueOf(name());
    }
}
