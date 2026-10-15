package com.company.testgen.model;

/**
 * Per-job label recording which validation tiers actually ran (§6.1, §6.2, §6.3, §6.13).
 */
public enum ConfidenceTier {
    VERIFIED,
    STATIC_ONLY,
    STATIC_ONLY_DEGRADED;

    /**
     * Determines the confidence tier from the verification result and parsed test plan (§6.3, §6.8, §6.13).
     *
     * @param verify the L2 verification result
     * @param plan the parsed test plan containing page snapshots
     * @return VERIFIED if L2 passed; STATIC_ONLY_DEGRADED if degraded (no HTML or empty snapshots); else STATIC_ONLY
     */
    public static ConfidenceTier from(VerificationResult verify, ParsedTestPlan plan) {
        if (verify != null && verify.isPassed()) {
            return VERIFIED;
        }
        boolean degraded = plan == null
                || plan.snapshots() == null
                || plan.snapshots().isEmpty()
                || plan.snapshots().stream().anyMatch(s -> !s.hasHtml());
        return degraded ? STATIC_ONLY_DEGRADED : STATIC_ONLY;
    }

    public static ConfidenceTier from(VerificationResult verify, boolean isDegraded) {
        if (verify != null && verify.isPassed()) {
            return VERIFIED;
        }
        return isDegraded ? STATIC_ONLY_DEGRADED : STATIC_ONLY;
    }
}
