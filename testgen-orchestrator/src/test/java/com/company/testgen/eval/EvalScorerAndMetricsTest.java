package com.company.testgen.eval;

import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.TokenUsage;
import com.company.testgen.orchestrator.PipelineOutcome;
import com.company.testgen.persistence.EvalCaseResultRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class EvalScorerAndMetricsTest {

    @Test
    @DisplayName("EvalScorer performs semantic exact matching and strategy matching (§8.3)")
    void testScorerMatching() {
        EvalScorer scorer = new EvalScorer();

        Map<String, ResolvedLocator> expected = Map.of(
                "btn-1", new ResolvedLocator("btn-1", LocatorStrategy.TEST_ID, "submit-btn"),
                "input-1", new ResolvedLocator("input-1", LocatorStrategy.LABEL, "User Name"),
                "role-1", new ResolvedLocator("role-1", LocatorStrategy.ROLE_NAME, "Save", "button")
        );

        EvalCase evalCase = new EvalCase(
                "case-1", "area", "desc", "", null, "medium", List.of(), "", "active",
                "", null, expected, null, null
        );

        // Actual matches:
        // btn-1: exact match with whitespace normalization
        // input-1: strategy match only (different value)
        // role-1: exact match with role case insensitivity
        List<ResolvedLocator> actualLocators = List.of(
                new ResolvedLocator("btn-1", LocatorStrategy.TEST_ID, "  submit-btn  "),
                new ResolvedLocator("input-1", LocatorStrategy.LABEL, "Other Label"),
                new ResolvedLocator("role-1", LocatorStrategy.ROLE_NAME, "Save", "BUTTON")
        );

        PipelineOutcome outcome = PipelineOutcome.success(
                UUID.randomUUID(),
                "DONE",
                new ResolvedLocatorSet(actualLocators),
                List.of(),
                1200L,
                1,
                new TokenUsage(500, 100, 200, 300)
        );

        CaseScore score = scorer.score(evalCase, outcome);

        assertThat(score.caseId()).isEqualTo("case-1");
        assertThat(score.firstAttemptPass()).isTrue();
        assertThat(score.attemptsUsed()).isEqualTo(1);
        assertThat(score.escalated()).isFalse();
        assertThat(score.locatorExactMatches()).isEqualTo(2); // btn-1 and role-1
        assertThat(score.locatorStrategyMatches()).isEqualTo(3); // all 3 matched strategy
        assertThat(score.referenceLocatorCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("EvalMetrics aggregates deterministic metrics, failure histogram, and per_case_variance (§8.3)")
    void testMetricsAggregation() {
        UUID runId = UUID.randomUUID();

        // Case 1: 2 repeats, both pass first attempt
        EvalCaseResultRecord c1_r0 = new EvalCaseResultRecord(
                1L, runId, "case-1", 0, true, 1, false, 5, 5, 5,
                100, 50, 200, 1000, List.of()
        );
        EvalCaseResultRecord c1_r1 = new EvalCaseResultRecord(
                2L, runId, "case-1", 1, true, 1, false, 5, 5, 5,
                100, 50, 200, 1100, List.of()
        );

        // Case 2: 2 repeats, 1 passes on first attempt, 1 escalates with failure codes
        EvalCaseResultRecord c2_r0 = new EvalCaseResultRecord(
                3L, runId, "case-2", 0, true, 1, false, 4, 4, 5,
                100, 50, 200, 1200, List.of()
        );
        EvalCaseResultRecord c2_r1 = new EvalCaseResultRecord(
                4L, runId, "case-2", 1, false, 3, true, 2, 3, 5,
                200, 100, 400, 2500, List.of("NOT_UNIQUE", "BANNED_PATTERN")
        );

        List<EvalCaseResultRecord> records = List.of(c1_r0, c1_r1, c2_r0, c2_r1);

        EvalMetrics metrics = EvalMetrics.aggregate(records);

        // 3 of 4 runs passed on first attempt
        assertThat(metrics.firstAttemptPassRate()).isEqualTo(0.75);
        // 1 of 4 runs escalated
        assertThat(metrics.escalationRate()).isEqualTo(0.25);
        // Total exact matches: 5 + 5 + 4 + 2 = 16 out of 20 reference locators
        assertThat(metrics.locatorExactMatchRate()).isEqualTo(16.0 / 20.0);
        // Strategy matches: 5 + 5 + 4 + 3 = 17 out of 20
        assertThat(metrics.locatorStrategyMatchRate()).isEqualTo(17.0 / 20.0);
        // Mean attempts: (1 + 1 + 1 + 3) / 4 = 1.5
        assertThat(metrics.meanAttempts()).isEqualTo(1.5);
        // Cache read ratio: total cacheRead = 200 + 200 + 200 + 400 = 1000, total input = 500 => 1000 / 1500 = 0.6667
        assertThat(metrics.cacheReadRatio()).isCloseTo(1000.0 / 1500.0, offset(0.001));

        // Failure code histogram
        assertThat(metrics.failureCodeHistogram())
                .containsEntry("NOT_UNIQUE", 1)
                .containsEntry("BANNED_PATTERN", 1);

        // Per-case variance:
        // case-1: p = 1.0 => var = 1.0 * 0.0 = 0.0
        // case-2: p = 0.5 => var = 0.5 * 0.5 = 0.25
        assertThat(metrics.perCaseVariance().get("case-1")).isEqualTo(0.0);
        assertThat(metrics.perCaseVariance().get("case-2")).isEqualTo(0.25);

        List<String> unstable = metrics.casesWithVarianceAbove(0.25);
        assertThat(unstable).containsExactly("case-2");

        // Verify JSON serialization round-trip
        String json = metrics.toJson();
        EvalMetrics deserialized = EvalMetrics.fromJson(json);
        assertThat(deserialized.firstAttemptPassRate()).isEqualTo(metrics.firstAttemptPassRate());
        assertThat(deserialized.locatorExactMatchRate()).isEqualTo(metrics.locatorExactMatchRate());
        assertThat(deserialized.escalationRate()).isEqualTo(metrics.escalationRate());
    }
}
