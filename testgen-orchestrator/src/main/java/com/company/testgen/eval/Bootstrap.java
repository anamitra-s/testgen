package com.company.testgen.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Bootstrap resampling over evaluation cases (§8.4).
 * Resamples CASES (not case-repeats) with replacement so the confidence interval
 * accounts for case-level heterogeneity without requiring a distributional assumption.
 * Emits a warning when fewer than 10 cases are evaluated (statistically underpowered).
 */
public final class Bootstrap {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    public static final int SMALL_SAMPLE_THRESHOLD = 10;
    private static final int DEFAULT_RESAMPLES = 10_000;
    private static final double DEFAULT_CONFIDENCE = 0.95;

    private Bootstrap() {}

    /**
     * Computes the difference confidence interval for (candidate - baseline).
     * Handles paired case differences across candidate and baseline.
     *
     * @param candidatePerCase per-case metrics for candidate run
     * @param baselinePerCase  per-case metrics for baseline run
     * @param resamples        number of bootstrap resamples (typically 10,000)
     * @param confidenceLevel  confidence level (e.g. 0.95 for 95% CI)
     * @return Interval with point estimate, lower, upper bounds, and underpowered flag
     */
    public static Interval differenceCi(
            Map<String, Double> candidatePerCase,
            Map<String, Double> baselinePerCase,
            int resamples,
            double confidenceLevel
    ) {
        return differenceCi(candidatePerCase, baselinePerCase, null, resamples, confidenceLevel);
    }

    /**
     * Computes the difference confidence interval for (candidate - baseline) with optional stratification.
     *
     * @param candidatePerCase per-case metrics for candidate run
     * @param baselinePerCase  per-case metrics for baseline run
     * @param caseStrata       optional map of caseId -> stratum (e.g. "difficulty:targetArea")
     * @param resamples        number of bootstrap resamples
     * @param confidenceLevel  confidence level (e.g. 0.95)
     * @return Interval with point estimate, lower, upper bounds, and underpowered flag
     */
    public static Interval differenceCi(
            Map<String, Double> candidatePerCase,
            Map<String, Double> baselinePerCase,
            Map<String, String> caseStrata,
            int resamples,
            double confidenceLevel
    ) {
        Objects.requireNonNull(candidatePerCase, "candidatePerCase cannot be null");
        Objects.requireNonNull(baselinePerCase, "baselinePerCase cannot be null");

        if (resamples <= 0) {
            resamples = DEFAULT_RESAMPLES;
        }
        if (confidenceLevel <= 0.0 || confidenceLevel >= 1.0) {
            confidenceLevel = DEFAULT_CONFIDENCE;
        }

        Set<String> allCases = new LinkedHashSet<>();
        allCases.addAll(candidatePerCase.keySet());
        allCases.addAll(baselinePerCase.keySet());

        if (allCases.isEmpty()) {
            return new Interval(0.0, 0.0, 0.0, true);
        }

        int n = allCases.size();
        boolean underpowered = n < SMALL_SAMPLE_THRESHOLD;
        if (underpowered) {
            log.warn("Small sample size (n={} < 10): 95% confidence interval on difference of proportions is statistically underpowered (§8.4)", n);
        }

        List<String> caseList = new ArrayList<>(allCases);
        double[] diffs = new double[n];
        double sumDiff = 0.0;

        for (int i = 0; i < n; i++) {
            String caseId = caseList.get(i);
            double c = candidatePerCase.getOrDefault(caseId, 0.0);
            if (Double.isNaN(c) || Double.isInfinite(c)) c = 0.0;
            double b = baselinePerCase.getOrDefault(caseId, 0.0);
            if (Double.isNaN(b) || Double.isInfinite(b)) b = 0.0;
            diffs[i] = c - b;
            sumDiff += diffs[i];
        }

        double pointEstimate = sumDiff / n;
        if (Double.isNaN(pointEstimate) || Double.isInfinite(pointEstimate)) {
            pointEstimate = 0.0;
        }

        // Single-case & zero-variance safety (§8.4, Assumption A-04):
        // If n == 1 or all case differences are identical, the CI has zero width around pointEstimate
        boolean zeroVariance = true;
        for (int i = 1; i < n; i++) {
            if (Double.compare(diffs[i], diffs[0]) != 0) {
                zeroVariance = false;
                break;
            }
        }
        if (zeroVariance) {
            return new Interval(pointEstimate, pointEstimate, pointEstimate, underpowered);
        }

        // Group indices by stratum if stratification metadata is supplied
        boolean stratified = caseStrata != null && !caseStrata.isEmpty();
        Map<String, List<Integer>> strataIndices = new LinkedHashMap<>();
        if (stratified) {
            for (int i = 0; i < n; i++) {
                String stratum = caseStrata.getOrDefault(caseList.get(i), "default");
                strataIndices.computeIfAbsent(stratum, k -> new ArrayList<>()).add(i);
            }
        }

        // Perform bootstrap resampling of cases with replacement
        double[] bootMeans = new double[resamples];
        SplittableRandom random = new SplittableRandom(42L); // Fixed seed for reproducible CI

        for (int r = 0; r < resamples; r++) {
            double bootSum = 0.0;
            if (stratified) {
                // Stratified resampling: resample within each stratum with its original stratum sample size
                for (List<Integer> group : strataIndices.values()) {
                    int groupSize = group.size();
                    if (groupSize == 0) continue;
                    for (int g = 0; g < groupSize; g++) {
                        int picked = group.get(random.nextInt(groupSize));
                        bootSum += diffs[picked];
                    }
                }
            } else {
                // Unstratified paired case resampling
                for (int i = 0; i < n; i++) {
                    int sampleIdx = random.nextInt(n);
                    bootSum += diffs[sampleIdx];
                }
            }
            bootMeans[r] = bootSum / n;
        }

        Arrays.sort(bootMeans);

        double alpha = 1.0 - confidenceLevel;
        int lowerIdx = (int) Math.max(0, Math.floor((alpha / 2.0) * resamples));
        int upperIdx = (int) Math.min(resamples - 1, Math.ceil((1.0 - (alpha / 2.0)) * resamples) - 1);

        double lower = bootMeans[lowerIdx];
        double upper = bootMeans[upperIdx];

        if (Double.isNaN(lower) || Double.isInfinite(lower)) lower = pointEstimate;
        if (Double.isNaN(upper) || Double.isInfinite(upper)) upper = pointEstimate;

        return new Interval(pointEstimate, lower, upper, underpowered);
    }

    public static Interval differenceCi(Map<String, Double> candidatePerCase, Map<String, Double> baselinePerCase) {
        return differenceCi(candidatePerCase, baselinePerCase, DEFAULT_RESAMPLES, DEFAULT_CONFIDENCE);
    }
}
