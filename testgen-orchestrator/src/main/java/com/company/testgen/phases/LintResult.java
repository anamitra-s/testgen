package com.company.testgen.phases;

import java.util.List;
import java.util.Objects;

/**
 * Result of the lint phase checks (§6.12).
 */
public final class LintResult {
    private final boolean passed;
    private final List<String> errors;

    public LintResult(boolean passed, List<String> errors) {
        this.passed = passed;
        this.errors = errors != null ? List.copyOf(errors) : List.of();
    }

    public static LintResult pass() {
        return new LintResult(true, List.of());
    }

    public static LintResult success() {
        return new LintResult(true, List.of());
    }

    public static LintResult failed(List<String> errors) {
        return new LintResult(false, errors);
    }

    public static LintResult failed(String singleError) {
        return new LintResult(false, List.of(singleError));
    }

    public boolean passed() {
        return passed;
    }

    public boolean isPassed() {
        return passed;
    }

    public List<String> errors() {
        return errors;
    }

    public String report() {
        if (passed) {
            return "Lint checks passed successfully.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Lint validation failed with ").append(errors.size()).append(" error(s):\n");
        for (int i = 0; i < errors.size(); i++) {
            sb.append(i + 1).append(". ").append(errors.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LintResult that)) return false;
        return passed == that.passed && Objects.equals(errors, that.errors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(passed, errors);
    }

    @Override
    public String toString() {
        return "LintResult[passed=" + passed + ", errors=" + errors + "]";
    }
}
