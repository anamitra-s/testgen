package com.company.testgen.eval;

/**
 * Statistical confidence interval (§8.4).
 */
public record Interval(
        double point,
        double lower,
        double upper
) {
    public boolean contains(double value) {
        return value >= lower && value <= upper;
    }
}
