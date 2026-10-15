package com.company.testgen.eval;

import com.company.testgen.persistence.EvalCaseResultRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Statistical Evaluation Gate implementing non-inferiority testing and guardrails (§8.4, §8.7).
 * - Non-inferiority bootstrap test (MARGIN = 0.05, ALPHA = 0.05) over cases
 * - 90-day long-horizon ratchet drift check (0.10 margin)
 * - 20% cost guardrail
 * - Unstable case detection (variance >= 0.25 on > 10% of cases)
 * - Automatic staleness quarantine (>= 3 consecutive nightly failures)
 */
public class EvalGate {

    private static final Logger log = LoggerFactory.getLogger(EvalGate.class);

    public static final double MARGIN = 0.05;              // 5 percentage points tolerated regression
    public static final double ALPHA = 0.05;               // 95% confidence
    public static final double LONG_HORIZON_MARGIN = 0.10; // 10 percentage points long-horizon margin (§8.5)
    public static final double COST_TOLERANCE = 1.20;      // 20% cost drift guardrail
    public static final double VARIANCE_THRESHOLD = 0.25;  // Unstable case threshold
    public static final double UNSTABLE_RATIO_MAX = 0.10;  // 10% max unstable cases
    public static final int BOOTSTRAP_RESAMPLES = 10_000;

    private final double margin;
    private final double alpha;
    private final double longHorizonMargin;
    private final int resamples;

    public EvalGate() {
        this(MARGIN, ALPHA, LONG_HORIZON_MARGIN, BOOTSTRAP_RESAMPLES);
    }

    public EvalGate(double margin, double alpha, double longHorizonMargin, int resamples) {
        this.margin = margin;
        this.alpha = alpha;
        this.longHorizonMargin = longHorizonMargin;
        this.resamples = resamples > 0 ? resamples : BOOTSTRAP_RESAMPLES;
    }

    public GateOutcome evaluate(EvalRun candidate, Optional<EvalRun> baseline) {
        return evaluate(candidate, baseline, Optional.empty());
    }

    /**
     * Evaluates a candidate run against baseline and optional 90-day long-horizon baseline (§8.4, §8.5).
     */
    public GateOutcome evaluate(EvalRun candidate, Optional<EvalRun> baseline, Optional<EvalRun> longHorizonBaseline) {
        if (candidate == null) {
            return GateOutcome.fail(List.of("Candidate evaluation run cannot be null"));
        }
        if (baseline == null || baseline.isEmpty()) {
            return GateOutcome.noBaseline("No baseline for this suite — recording candidate as baseline.", candidate);
        }

        EvalRun base = baseline.get();
        List<String> failures = new ArrayList<>();

        // 1. Bootstrap difference in proportions for primary gating metrics (resampling CASES)
        for (Metric m : List.of(Metric.FIRST_ATTEMPT_PASS_RATE, Metric.LOCATOR_EXACT_MATCH_RATE)) {
            Interval ci = Bootstrap.differenceCi(
                    candidate.perCase(m), base.perCase(m), resamples, 1.0 - alpha);

            // Non-inferiority: the LOWER bound of (candidate - baseline) must exceed -MARGIN
            if (ci.lower() < -margin) {
                failures.add(String.format("%s regressed: Δ=%.3f (95%% CI %.3f..%.3f), margin -%.2f",
                        m, ci.point(), ci.lower(), ci.upper(), margin));
            }
        }

        // 2. Escalation rate is inverted (upper bound must not exceed +MARGIN)
        Interval esc = Bootstrap.differenceCi(
                candidate.perCase(Metric.ESCALATION_RATE), base.perCase(Metric.ESCALATION_RATE), resamples, 1.0 - alpha);
        if (esc.upper() > margin) {
            failures.add(String.format("escalation_rate regressed: Δ=%.3f (upper %.3f), margin +%.2f",
                    esc.point(), esc.upper(), margin));
        }

        // 3. 90-day long-horizon baseline check (catches ratchet drift, margin 0.10) (§8.5)
        if (longHorizonBaseline != null && longHorizonBaseline.isPresent()) {
            EvalRun longHorizon = longHorizonBaseline.get();
            for (Metric m : List.of(Metric.FIRST_ATTEMPT_PASS_RATE, Metric.LOCATOR_EXACT_MATCH_RATE)) {
                Interval ciLong = Bootstrap.differenceCi(
                        candidate.perCase(m), longHorizon.perCase(m), resamples, 1.0 - alpha);
                if (ciLong.lower() < -longHorizonMargin) {
                    failures.add(String.format("Long-horizon (90d) %s regressed vs baseline %s: Δ=%.3f (95%% CI %.3f..%.3f), margin -%.2f",
                            m, longHorizon.runId(), ciLong.point(), ciLong.lower(), ciLong.upper(), longHorizonMargin));
                }
            }

            Interval escLong = Bootstrap.differenceCi(
                    candidate.perCase(Metric.ESCALATION_RATE), longHorizon.perCase(Metric.ESCALATION_RATE), resamples, 1.0 - alpha);
            if (escLong.upper() > longHorizonMargin) {
                failures.add(String.format("Long-horizon (90d) escalation_rate regressed vs baseline %s: Δ=%.3f (upper %.3f), margin +%.2f",
                        longHorizon.runId(), escLong.point(), escLong.upper(), longHorizonMargin));
            }
        }

        // 4. Cost guardrail (allow 20% drift before blocking) (§8.4)
        if (base.meanCostUsd().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal costLimit = base.meanCostUsd().multiply(BigDecimal.valueOf(COST_TOLERANCE));
            if (candidate.meanCostUsd().compareTo(costLimit) > 0) {
                double pctDelta = candidate.meanCostUsd().subtract(base.meanCostUsd())
                        .divide(base.meanCostUsd(), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
                failures.add(String.format("cost/case up %.0f%% vs baseline (guardrail max 20%%)", pctDelta));
            }
        }

        // 5. Unstable case detection across repeats (§8.4)
        List<String> unstable = candidate.casesWithVarianceAbove(VARIANCE_THRESHOLD);
        if (unstable.size() > candidate.caseCount() * UNSTABLE_RATIO_MAX) {
            failures.add(String.format("%d/%d cases unstable across repeats — corpus needs attention: %s",
                    unstable.size(), candidate.caseCount(), unstable));
        }

        return failures.isEmpty()
                ? GateOutcome.pass(candidate, base)
                : GateOutcome.fail(failures, candidate, base);
    }

    /**
     * Automatic staleness quarantine (§8.7):
     * A case failing on >= 3 consecutive nightly runs with unchanged model and prompt bundle
     * is auto-quarantined (status: quarantined), excluded from the gate, and reported.
     *
     * @param recentNightlyRuns list of recent nightly runs (ordered newest first)
     * @param corpus            the golden corpus instance
     * @return list of newly quarantined case IDs
     */
    public List<String> checkAndApplyStalenessQuarantine(List<EvalRun> recentNightlyRuns, Corpus corpus) {
        if (recentNightlyRuns == null || recentNightlyRuns.size() < 3 || corpus == null) {
            return List.of();
        }

        List<String> quarantined = new ArrayList<>();
        // Inspect the last 3 nightly runs
        List<EvalRun> last3 = recentNightlyRuns.subList(0, 3);
        String modelId = last3.get(0).modelId();
        String promptHash = last3.get(0).promptBundleHash();

        // Check if all 3 runs had unchanged prompt bundle and model
        boolean unchanged = last3.stream()
                .allMatch(r -> modelId.equals(r.modelId()) && promptHash.equals(r.promptBundleHash()));

        if (!unchanged) {
            log.debug("Model or prompt changed across recent nightly runs; skipping staleness quarantine check");
            return List.of();
        }

        // Identify cases present in active corpus that failed in all 3 runs
        Map<String, Integer> consecutiveFails = new LinkedHashMap<>();
        for (EvalRun run : last3) {
            for (EvalCaseResultRecord cr : run.caseResults()) {
                if (!cr.firstAttemptPass() || cr.escalated()) {
                    consecutiveFails.put(cr.caseId(), consecutiveFails.getOrDefault(cr.caseId(), 0) + 1);
                }
            }
        }

        for (EvalCase c : corpus.cases()) {
            if (c.isActive() && consecutiveFails.getOrDefault(c.caseId(), 0) >= 3) {
                log.warn("Auto-quarantining case {} due to 3 consecutive nightly failures", c.caseId());
                corpus.quarantineCase(c.caseId());
                quarantined.add(c.caseId());
            }
        }

        return quarantined;
    }

    public double margin() {
        return margin;
    }

    public double alpha() {
        return alpha;
    }

    public double longHorizonMargin() {
        return longHorizonMargin;
    }
}
