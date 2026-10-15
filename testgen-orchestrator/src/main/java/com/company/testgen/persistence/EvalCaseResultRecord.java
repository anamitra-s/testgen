package com.company.testgen.persistence;

import java.util.List;
import java.util.UUID;

/**
 * Persisted row representing a per-case evaluation result from {@code eval_case_results} (§6.14, §8.3).
 */
public record EvalCaseResultRecord(
        Long id,
        UUID runId,
        String caseId,
        int repeatNo,
        boolean firstAttemptPass,
        int attemptsUsed,
        boolean escalated,
        int locatorExactMatches,
        int locatorStrategyMatches,
        int referenceLocatorCount,
        int inputTokens,
        int outputTokens,
        int cacheReadTokens,
        int durationMs,
        List<String> failureCodes
) {
    public EvalCaseResultRecord {
        if (failureCodes == null) {
            failureCodes = List.of();
        } else {
            failureCodes = List.copyOf(failureCodes);
        }
    }
}
