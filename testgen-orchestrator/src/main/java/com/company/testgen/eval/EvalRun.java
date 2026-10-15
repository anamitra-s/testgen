package com.company.testgen.eval;

import com.company.testgen.persistence.EvalCaseResultRecord;
import com.company.testgen.persistence.EvalRunRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain representation of an evaluation run including its case results and aggregated metrics (§8.2, §8.4, §8.5).
 */
public record EvalRun(
        UUID runId,
        String suite,
        String trigger,
        String modelId,
        String promptBundleHash,
        String orchestratorVersion,
        String gitRef,
        int repeats,
        int caseCount,
        boolean isBaseline,
        EvalMetrics metrics,
        List<EvalCaseResultRecord> caseResults,
        Instant startedAt,
        Instant finishedAt
) {
    public EvalRun {
        Objects.requireNonNull(runId, "runId cannot be null");
        suite = suite != null ? suite : "golden";
        trigger = trigger != null ? trigger : "NIGHTLY";
        modelId = modelId != null ? modelId : "unknown";
        promptBundleHash = promptBundleHash != null ? promptBundleHash : "unknown";
        orchestratorVersion = orchestratorVersion != null ? orchestratorVersion : "1.0.0";
        caseResults = caseResults != null ? List.copyOf(caseResults) : List.of();
        if (metrics == null) {
            metrics = EvalMetrics.aggregate(caseResults);
        }
    }

    public static EvalRun create(
            UUID runId,
            String suite,
            String trigger,
            String modelId,
            String promptBundleHash,
            int repeats,
            List<EvalCaseResultRecord> caseResults
    ) {
        EvalMetrics metrics = EvalMetrics.aggregate(caseResults);
        return new EvalRun(
                runId,
                suite,
                trigger,
                modelId,
                promptBundleHash,
                "1.0.0",
                "main",
                repeats,
                metrics.totalCases(),
                false,
                metrics,
                caseResults,
                Instant.now(),
                Instant.now()
        );
    }

    public Map<String, Double> perCase(Metric m) {
        if (metrics == null) {
            return Collections.emptyMap();
        }
        return switch (m) {
            case FIRST_ATTEMPT_PASS_RATE -> metrics.perCasePassRate();
            case LOCATOR_EXACT_MATCH_RATE -> metrics.perCaseExactMatchRate();
            case ESCALATION_RATE -> metrics.perCaseEscalationRate();
        };
    }

    public List<String> casesWithVarianceAbove(double threshold) {
        if (metrics == null) {
            return List.of();
        }
        return metrics.casesWithVarianceAbove(threshold);
    }

    public BigDecimal meanCostUsd() {
        return metrics != null ? metrics.costPerCase() : BigDecimal.ZERO;
    }

    public EvalRunRecord toRecord(String gateOutcome, String gateDetailJson) {
        return new EvalRunRecord(
                runId,
                suite,
                trigger,
                modelId,
                promptBundleHash,
                orchestratorVersion,
                gitRef,
                repeats,
                caseCount,
                isBaseline,
                metrics != null ? metrics.toJson() : "{}",
                gateOutcome,
                gateDetailJson,
                startedAt,
                finishedAt
        );
    }

    public static EvalRun fromRecord(EvalRunRecord record, List<EvalCaseResultRecord> caseResults) {
        EvalMetrics m = null;
        if (record.metricsJson() != null && !record.metricsJson().isBlank()) {
            try {
                m = EvalMetrics.fromJson(record.metricsJson());
            } catch (Exception ignored) {}
        }
        if (m == null) {
            m = EvalMetrics.aggregate(caseResults);
        }
        return new EvalRun(
                record.id(),
                record.suite(),
                record.trigger(),
                record.modelId(),
                record.promptBundleHash(),
                record.orchestratorVersion(),
                record.gitRef(),
                record.repeats(),
                record.caseCount(),
                record.isBaseline(),
                m,
                caseResults != null ? caseResults : List.of(),
                record.startedAt(),
                record.finishedAt()
        );
    }
}
