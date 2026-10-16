package com.company.testgen.eval;

import com.company.testgen.persistence.EvalCaseResultRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class BootstrapAndEvalGateTest {

    @Test
    @DisplayName("Bootstrap computes difference CI across 10,000 case resamples (§8.4)")
    void testBootstrapDifferenceCi() {
        Map<String, Double> candidate = new LinkedHashMap<>();
        Map<String, Double> baseline = new LinkedHashMap<>();

        // Generate 30 cases where candidate is slightly better or equal
        for (int i = 0; i < 30; i++) {
            candidate.put("case-" + i, 0.85);
            baseline.put("case-" + i, 0.80);
        }

        Interval ci = Bootstrap.differenceCi(candidate, baseline, 10_000, 0.95);

        assertThat(ci.point()).isCloseTo(0.05, offset(0.001));
        assertThat(ci.lower()).isCloseTo(0.05, offset(0.01));
        assertThat(ci.upper()).isCloseTo(0.05, offset(0.01));
    }

    @Test
    @DisplayName("Bootstrap computes exact difference CI for identical candidate and baseline")
    void testIdenticalDistributions() {
        Map<String, Double> candidate = new LinkedHashMap<>();
        Map<String, Double> baseline = new LinkedHashMap<>();

        for (int i = 0; i < 25; i++) {
            candidate.put("case-" + i, 0.80);
            baseline.put("case-" + i, 0.80);
        }

        Interval ci = Bootstrap.differenceCi(candidate, baseline, 10_000, 0.95);

        assertThat(ci.point()).isCloseTo(0.0, offset(0.001));
        assertThat(ci.lower()).isCloseTo(0.0, offset(0.001));
        assertThat(ci.upper()).isCloseTo(0.0, offset(0.001));
    }

    @Test
    @DisplayName("Bootstrap computes positive CI when candidate is consistently superior")
    void testSuperiorCandidate() {
        Map<String, Double> candidate = new LinkedHashMap<>();
        Map<String, Double> baseline = new LinkedHashMap<>();

        for (int i = 0; i < 30; i++) {
            candidate.put("case-" + i, 0.90);
            baseline.put("case-" + i, 0.75);
        }

        Interval ci = Bootstrap.differenceCi(candidate, baseline, 10_000, 0.95);

        assertThat(ci.point()).isCloseTo(0.15, offset(0.001));
        assertThat(ci.lower()).isCloseTo(0.15, offset(0.01));
        assertThat(ci.upper()).isCloseTo(0.15, offset(0.01));
    }

    @Test
    @DisplayName("Bootstrap computes negative CI when candidate is inferior (regression)")
    void testInferiorCandidate() {
        Map<String, Double> candidate = new LinkedHashMap<>();
        Map<String, Double> baseline = new LinkedHashMap<>();

        for (int i = 0; i < 30; i++) {
            candidate.put("case-" + i, 0.60);
            baseline.put("case-" + i, 0.85);
        }

        Interval ci = Bootstrap.differenceCi(candidate, baseline, 10_000, 0.95);

        assertThat(ci.point()).isCloseTo(-0.25, offset(0.001));
        assertThat(ci.lower()).isCloseTo(-0.25, offset(0.01));
        assertThat(ci.upper()).isCloseTo(-0.25, offset(0.01));
    }

    @Test
    @DisplayName("Bootstrap produces wider confidence interval for 99% CI than 90% CI on heterogeneous data")
    void testConfidenceLevelsWidth() {
        Map<String, Double> candidate = new LinkedHashMap<>();
        Map<String, Double> baseline = new LinkedHashMap<>();

        // Heterogeneous cases: some better, some worse, some equal
        for (int i = 0; i < 50; i++) {
            double c = 0.5 + (i % 5) * 0.1;
            double b = 0.5 + ((i + 2) % 5) * 0.1;
            candidate.put("case-" + i, c);
            baseline.put("case-" + i, b);
        }

        Interval ci90 = Bootstrap.differenceCi(candidate, baseline, 10_000, 0.90);
        Interval ci99 = Bootstrap.differenceCi(candidate, baseline, 10_000, 0.99);

        assertThat(ci90.point()).isEqualTo(ci99.point());
        double width90 = ci90.upper() - ci90.lower();
        double width99 = ci99.upper() - ci99.lower();

        assertThat(width99).isGreaterThan(width90);
        assertThat(ci90.lower()).isLessThanOrEqualTo(ci90.point());
        assertThat(ci90.upper()).isGreaterThanOrEqualTo(ci90.point());
    }

    @Test
    @DisplayName("Bootstrap fixed random seed guarantees deterministic reproducible CI")
    void testSeedReproducibility() {
        Map<String, Double> candidate = Map.of("c1", 0.9, "c2", 0.7, "c3", 0.85);
        Map<String, Double> baseline = Map.of("c1", 0.8, "c2", 0.75, "c3", 0.8);

        Interval ci1 = Bootstrap.differenceCi(candidate, baseline, 5000, 0.95);
        Interval ci2 = Bootstrap.differenceCi(candidate, baseline, 5000, 0.95);

        assertThat(ci1.point()).isEqualTo(ci2.point());
        assertThat(ci1.lower()).isEqualTo(ci2.lower());
        assertThat(ci1.upper()).isEqualTo(ci2.upper());
    }

    @Test
    @DisplayName("Bootstrap gracefully handles empty case maps")
    void testEmptyMaps() {
        Interval ci = Bootstrap.differenceCi(Map.of(), Map.of(), 1000, 0.95);
        assertThat(ci.point()).isEqualTo(0.0);
        assertThat(ci.lower()).isEqualTo(0.0);
        assertThat(ci.upper()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("EvalGate passes when candidate is non-inferior within MARGIN = 0.05 (§8.4)")
    void testGatePassesWithinMargin() {
        EvalGate gate = new EvalGate();

        List<EvalCaseResultRecord> baseRecords = createSampleRecords(30, true, false, 5, 5, 0.001);
        List<EvalCaseResultRecord> candRecords = createSampleRecords(30, true, false, 5, 5, 0.001);

        EvalRun baseline = EvalRun.create(UUID.randomUUID(), "golden", "NIGHTLY", "model-1", "bundle-1", 1, baseRecords);
        EvalRun candidate = EvalRun.create(UUID.randomUUID(), "golden", "PR", "model-1", "bundle-1", 1, candRecords);

        GateOutcome outcome = gate.evaluate(candidate, Optional.of(baseline));

        assertThat(outcome.isPass()).isTrue();
        assertThat(outcome.failures()).isEmpty();
    }

    @Test
    @DisplayName("EvalGate fails when pass rate or exact match regresses beyond 0.05 margin (§8.4)")
    void testGateFailsOnQualityRegression() {
        EvalGate gate = new EvalGate();

        // Baseline: 100% pass rate
        List<EvalCaseResultRecord> baseRecords = createSampleRecords(30, true, false, 5, 5, 0.001);
        // Candidate: severe drop (only 50% pass rate)
        List<EvalCaseResultRecord> candRecords = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            boolean pass = i % 2 == 0;
            candRecords.add(new EvalCaseResultRecord(
                    (long) i, UUID.randomUUID(), "case-" + i, 0, pass, pass ? 1 : 3, !pass,
                    pass ? 5 : 2, 5, 5, 100, 50, 200, 1000, pass ? List.of() : List.of("NOT_UNIQUE")
            ));
        }

        EvalRun baseline = EvalRun.create(UUID.randomUUID(), "golden", "NIGHTLY", "model-1", "bundle-1", 1, baseRecords);
        EvalRun candidate = EvalRun.create(UUID.randomUUID(), "golden", "PR", "model-1", "bundle-1", 1, candRecords);

        GateOutcome outcome = gate.evaluate(candidate, Optional.of(baseline));

        assertThat(outcome.isFail()).isTrue();
        assertThat(outcome.failures()).anyMatch(f -> f.contains("first_attempt_pass_rate regressed"));
    }

    @Test
    @DisplayName("EvalGate enforces 20% cost guardrail (§8.4)")
    void testGateCostGuardrail() {
        EvalGate gate = new EvalGate();

        List<EvalCaseResultRecord> baseRecords = createSampleRecords(20, true, false, 5, 5, 0.001);
        // Candidate with 5x token usage => > 20% cost increase
        List<EvalCaseResultRecord> expensiveRecords = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            expensiveRecords.add(new EvalCaseResultRecord(
                    (long) i, UUID.randomUUID(), "case-" + i, 0, true, 1, false,
                    5, 5, 5, 50000, 20000, 0, 1000, List.of()
            ));
        }

        EvalRun baseline = EvalRun.create(UUID.randomUUID(), "golden", "NIGHTLY", "model-1", "bundle-1", 1, baseRecords);
        EvalRun candidate = EvalRun.create(UUID.randomUUID(), "golden", "PR", "model-1", "bundle-1", 1, expensiveRecords);

        GateOutcome outcome = gate.evaluate(candidate, Optional.of(baseline));

        assertThat(outcome.isFail()).isTrue();
        assertThat(outcome.failures()).anyMatch(f -> f.contains("cost/case up"));
    }

    @Test
    @DisplayName("EvalGate detects unstable cases when > 10% of cases have variance >= 0.25 (§8.4)")
    void testGateUnstableCorpusDetection() {
        EvalGate gate = new EvalGate();

        // 10 cases with 2 repeats each; 4 cases have 1 pass and 1 fail (variance = 0.25)
        // 4 / 10 = 40% > 10% threshold!
        List<EvalCaseResultRecord> records = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < 10; i++) {
            String caseId = "case-" + i;
            if (i < 4) {
                // Unstable case: repeat 0 passes, repeat 1 fails
                records.add(new EvalCaseResultRecord(id++, UUID.randomUUID(), caseId, 0, true, 1, false, 5, 5, 5, 100, 50, 0, 500, List.of()));
                records.add(new EvalCaseResultRecord(id++, UUID.randomUUID(), caseId, 1, false, 3, false, 5, 5, 5, 100, 50, 0, 500, List.of()));
            } else {
                // Stable case: both pass
                records.add(new EvalCaseResultRecord(id++, UUID.randomUUID(), caseId, 0, true, 1, false, 5, 5, 5, 100, 50, 0, 500, List.of()));
                records.add(new EvalCaseResultRecord(id++, UUID.randomUUID(), caseId, 1, true, 1, false, 5, 5, 5, 100, 50, 0, 500, List.of()));
            }
        }

        EvalRun run = EvalRun.create(UUID.randomUUID(), "golden", "PR", "model-1", "bundle-1", 2, records);
        GateOutcome outcome = gate.evaluate(run, Optional.of(run)); // compare against self

        assertThat(outcome.isFail()).isTrue();
        assertThat(outcome.failures()).anyMatch(f -> f.contains("unstable across repeats"));
    }

    @Test
    @DisplayName("EvalGate enforces 90-day long-horizon baseline check with 0.10 margin (§8.5)")
    void testLongHorizonBaselineCheck() {
        EvalGate gate = new EvalGate();

        List<EvalCaseResultRecord> baseRecords = createSampleRecords(30, true, false, 5, 5, 0.001);
        List<EvalCaseResultRecord> candRecords = createSampleRecords(30, true, false, 5, 5, 0.001);

        // Long horizon baseline had higher standard, and candidate is > 0.10 worse than long horizon
        List<EvalCaseResultRecord> oldRecords = createSampleRecords(30, true, false, 5, 5, 0.001);
        List<EvalCaseResultRecord> regressedRecords = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            boolean pass = i < 20; // 66.7% vs 100% in old baseline => 33.3% drop > 10% margin
            regressedRecords.add(new EvalCaseResultRecord(
                    (long) i, UUID.randomUUID(), "case-" + i, 0, pass, pass ? 1 : 3, !pass,
                    pass ? 5 : 2, 5, 5, 100, 50, 200, 1000, List.of()
            ));
        }

        EvalRun recentBaseline = EvalRun.create(UUID.randomUUID(), "golden", "NIGHTLY", "model-1", "bundle-1", 1, regressedRecords);
        EvalRun longHorizon = EvalRun.create(UUID.randomUUID(), "golden", "NIGHTLY", "model-1", "bundle-1", 1, oldRecords);
        EvalRun candidate = EvalRun.create(UUID.randomUUID(), "golden", "PR", "model-1", "bundle-1", 1, regressedRecords);

        // Pairwise against recentBaseline passes (since they are identical)
        GateOutcome pairwise = gate.evaluate(candidate, Optional.of(recentBaseline));
        assertThat(pairwise.isPass()).isTrue();

        // But 90-day long horizon catches the ratchet drift!
        GateOutcome longHorizonOutcome = gate.evaluate(candidate, Optional.of(recentBaseline), Optional.of(longHorizon));
        assertThat(longHorizonOutcome.isFail()).isTrue();
        assertThat(longHorizonOutcome.failures()).anyMatch(f -> f.contains("Long-horizon (90d)"));
    }

    @Test
    @DisplayName("Automatic staleness quarantine quarantines case failing 3 consecutive nightly runs (§8.7)")
    void testStalenessQuarantine() {
        EvalGate gate = new EvalGate();

        EvalCase case1 = new EvalCase("stale-case", "dash", "", "", null, "medium", List.of(), "", "active", "", null, null, null, null);
        EvalCase case2 = new EvalCase("healthy-case", "dash", "", "", null, "medium", List.of(), "", "active", "", null, null, null, null);

        Corpus corpus = new Corpus(Path.of("evals"), Map.of(), List.of(case1, case2));

        // 3 consecutive nightly runs with identical model and prompt
        List<EvalRun> nightlyRuns = new ArrayList<>();
        for (int runNo = 0; runNo < 3; runNo++) {
            List<EvalCaseResultRecord> recs = List.of(
                    // stale-case fails in all 3 runs
                    new EvalCaseResultRecord((long) runNo * 2, UUID.randomUUID(), "stale-case", 0, false, 3, true, 1, 1, 5, 100, 50, 0, 500, List.of("ELEMENT_NOT_FOUND")),
                    // healthy-case passes
                    new EvalCaseResultRecord((long) runNo * 2 + 1, UUID.randomUUID(), "healthy-case", 0, true, 1, false, 5, 5, 5, 100, 50, 0, 500, List.of())
            );
            nightlyRuns.add(EvalRun.create(UUID.randomUUID(), "golden", "NIGHTLY", "sonnet-5", "hash-abc", 1, recs));
        }

        List<String> quarantined = gate.checkAndApplyStalenessQuarantine(nightlyRuns, corpus);

        assertThat(quarantined).containsExactly("stale-case");
        assertThat(corpus.findCase("stale-case").orElseThrow().isQuarantined()).isTrue();
        assertThat(corpus.findCase("healthy-case").orElseThrow().isActive()).isTrue();
    }

    private List<EvalCaseResultRecord> createSampleRecords(int count, boolean pass, boolean escalated, int exact, int totalRef, double cost) {
        List<EvalCaseResultRecord> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new EvalCaseResultRecord(
                    (long) i, UUID.randomUUID(), "case-" + i, 0, pass, pass ? 1 : 3, escalated,
                    exact, exact, totalRef, 100, 50, 200, 1000, List.of()
            ));
        }
        return list;
    }
}
