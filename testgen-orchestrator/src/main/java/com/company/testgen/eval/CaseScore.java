package com.company.testgen.eval;

import com.company.testgen.model.TokenUsage;
import com.company.testgen.persistence.EvalCaseResultRecord;

import java.util.List;
import java.util.UUID;

/**
 * Score resulting from evaluating a single case run (§8.3).
 */
public record CaseScore(
        String caseId,
        boolean firstAttemptPass,
        int attemptsUsed,
        boolean escalated,
        int locatorExactMatches,
        int locatorStrategyMatches,
        int referenceLocatorCount,
        TokenUsage tokenUsage,
        long durationMs,
        List<String> blockingReasonCodes
) {
    public CaseScore {
        if (blockingReasonCodes == null) {
            blockingReasonCodes = List.of();
        } else {
            blockingReasonCodes = List.copyOf(blockingReasonCodes);
        }
        if (tokenUsage == null) {
            tokenUsage = new TokenUsage(0, 0, 0, 0);
        }
    }

    public EvalCaseResultRecord toRecord(UUID runId, int repeatNo) {
        return new EvalCaseResultRecord(
                null,
                runId,
                caseId,
                repeatNo,
                firstAttemptPass,
                attemptsUsed,
                escalated,
                locatorExactMatches,
                locatorStrategyMatches,
                referenceLocatorCount,
                tokenUsage.inputTokens(),
                tokenUsage.outputTokens(),
                tokenUsage.cacheReadTokens(),
                (int) durationMs,
                blockingReasonCodes
        );
    }
}
