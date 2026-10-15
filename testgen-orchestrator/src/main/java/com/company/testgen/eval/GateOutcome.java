package com.company.testgen.eval;

import java.util.Collections;
import java.util.List;

/**
 * Outcome resulting from statistical eval gating (§8.4).
 */
public record GateOutcome(
        String status,
        List<String> failures,
        String message,
        EvalRun candidate,
        EvalRun baseline
) {
    public static final String STATUS_PASS = "PASS";
    public static final String STATUS_FAIL = "FAIL";
    public static final String STATUS_NO_BASELINE = "NO_BASELINE";

    public GateOutcome {
        failures = failures != null ? List.copyOf(failures) : List.of();
    }

    public boolean isPass() {
        return STATUS_PASS.equalsIgnoreCase(status);
    }

    public boolean isFail() {
        return STATUS_FAIL.equalsIgnoreCase(status);
    }

    public boolean isNoBaseline() {
        return STATUS_NO_BASELINE.equalsIgnoreCase(status);
    }

    public static GateOutcome pass(EvalRun candidate, EvalRun baseline) {
        return new GateOutcome(STATUS_PASS, List.of(), "Statistical evaluation gate passed", candidate, baseline);
    }

    public static GateOutcome pass(EvalRun candidate) {
        return new GateOutcome(STATUS_PASS, List.of(), "Statistical evaluation gate passed", candidate, null);
    }

    public static GateOutcome fail(List<String> failures, EvalRun candidate, EvalRun baseline) {
        String msg = String.join("; ", failures);
        return new GateOutcome(STATUS_FAIL, failures, msg, candidate, baseline);
    }

    public static GateOutcome fail(List<String> failures) {
        return new GateOutcome(STATUS_FAIL, failures, String.join("; ", failures), null, null);
    }

    public static GateOutcome noBaseline(String message, EvalRun candidate) {
        return new GateOutcome(STATUS_NO_BASELINE, List.of(), message, candidate, null);
    }

    public static GateOutcome noBaseline(String message) {
        return new GateOutcome(STATUS_NO_BASELINE, List.of(), message, null, null);
    }
}
