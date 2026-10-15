package com.company.testgen.eval;

/**
 * Primary gating and comparison metrics (§8.3, §8.4).
 */
public enum Metric {
    FIRST_ATTEMPT_PASS_RATE("first_attempt_pass_rate"),
    LOCATOR_EXACT_MATCH_RATE("locator_exact_match_rate"),
    ESCALATION_RATE("escalation_rate");

    private final String key;

    Metric(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    @Override
    public String toString() {
        return key;
    }
}
