package com.company.testgen.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted row representing an evaluation run from {@code eval_runs} (§6.14, §8.2, §8.5).
 */
public record EvalRunRecord(
        UUID id,
        String suite,
        String trigger,
        String modelId,
        String promptBundleHash,
        String orchestratorVersion,
        String gitRef,
        int repeats,
        int caseCount,
        boolean isBaseline,
        String metricsJson,
        String gateOutcome,
        String gateDetailJson,
        Instant startedAt,
        Instant finishedAt
) {}
