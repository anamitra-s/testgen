package com.company.testgen.eval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Bootstrap resampling over evaluation cases (§8.4).
 * Resamples CASES (not case-repeats) with replacement so the confidence interval
 * accounts for case-level heterogeneity without requiring a distributional assumption.
 */
public final class Bootstrap {

    private static final int DEFAULT_RESAMPLES = 10_000;
    private static final double DEFAULT_CONFIDENCE = 0.95;

    private Bootstrap() {}

    /**
     * Computes the difference confidence interval for (candidate - baseline).
     *
     * @param candidatePerCase per-case metrics for candidate run
     * @param baselinePerCase  per-case metrics for baseline run
     * @param resamples        number of bootstrap resamples (typically 10,000)
     * @param confidenceLevel  confidence level (e.g. 0.95 for 95% CI)
     * @return Interval with point estimate, lower, and upper bounds
     */
    public static Interval differenceCi(
            Map<String, Double> candidatePerCase,
            Map<String, Double> baselinePerCase,
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
            return new Interval(0.0, 0.0, 0.0);
        }

        int n = allCases.size();
        double[] diffs = new double[n];
        int idx = 0;
        double sumDiff = 0.0;

        for (String caseId : allCases) {
            double c = candidatePerCase.getOrDefault(caseId, 0.0);
            double b = baselinePerCase.getOrDefault(caseId, 0.0);
            diffs[idx] = c - b;
            sumDiff += diffs[idx];
            idx++;
        }

        double pointEstimate = sumDiff / n;

        // Perform bootstrap resampling of cases with replacement
        double[] bootMeans = new double[resamples];
        SplittableRandom random = new SplittableRandom(42L); // Fixed seed for reproducible CI

        for (int r = 0; r < resamples; r++) {
            double bootSum = 0.0;
            for (int i = 0; i < n; i++) {
                int sampleIdx = random.nextInt(n);
                bootSum += diffs[sampleIdx];
            }
            bootMeans[r] = bootSum / n;
        }

        Arrays.sort(bootMeans);

        double alpha = 1.0 - confidenceLevel;
        int lowerIdx = (int) Math.max(0, Math.floor((alpha / 2.0) * resamples));
        int upperIdx = (int) Math.min(resamples - 1, Math.ceil((1.0 - (alpha / 2.0)) * resamples) - 1);

        double lower = bootMeans[lowerIdx];
        double upper = bootMeans[upperIdx];

        return new Interval(pointEstimate, lower, upper);
    }

    public static Interval differenceCi(Map<String, Double> candidatePerCase, Map<String, Double> baselinePerCase) {
        return differenceCi(candidatePerCase, baselinePerCase, DEFAULT_RESAMPLES, DEFAULT_CONFIDENCE);
    }
}
