package com.company.testgen.eval;

import com.company.testgen.persistence.EvalCaseResultRecord;
import com.company.testgen.util.Json;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Deterministic evaluation metrics aggregated across evaluation case runs (§8.3).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalMetrics(
        @JsonProperty("first_attempt_pass_rate") double firstAttemptPassRate,
        @JsonProperty("locator_exact_match_rate") double locatorExactMatchRate,
        @JsonProperty("escalation_rate") double escalationRate,
        @JsonProperty("locator_strategy_match_rate") double locatorStrategyMatchRate,
        @JsonProperty("mean_attempts") double meanAttempts,
        @JsonProperty("cache_read_ratio") double cacheReadRatio,
        @JsonProperty("failure_code_histogram") Map<String, Integer> failureCodeHistogram,
        @JsonProperty("per_case_variance") Map<String, Double> perCaseVariance,
        @JsonProperty("tokens_per_case") double tokensPerCase,
        @JsonProperty("cost_per_case") BigDecimal costPerCase,
        @JsonProperty("p95_duration_ms") long p95DurationMs,
        @JsonProperty("total_cases") int totalCases,
        @JsonProperty("total_runs") int totalRuns,
        @JsonProperty("per_case_pass_rate") Map<String, Double> perCasePassRate,
        @JsonProperty("per_case_exact_match_rate") Map<String, Double> perCaseExactMatchRate,
        @JsonProperty("per_case_escalation_rate") Map<String, Double> perCaseEscalationRate
) {
    public EvalMetrics {
        failureCodeHistogram = failureCodeHistogram != null ? Collections.unmodifiableMap(new TreeMap<>(failureCodeHistogram)) : Map.of();
        perCaseVariance = perCaseVariance != null ? Collections.unmodifiableMap(new LinkedHashMap<>(perCaseVariance)) : Map.of();
        perCasePassRate = perCasePassRate != null ? Collections.unmodifiableMap(new LinkedHashMap<>(perCasePassRate)) : Map.of();
        perCaseExactMatchRate = perCaseExactMatchRate != null ? Collections.unmodifiableMap(new LinkedHashMap<>(perCaseExactMatchRate)) : Map.of();
        perCaseEscalationRate = perCaseEscalationRate != null ? Collections.unmodifiableMap(new LinkedHashMap<>(perCaseEscalationRate)) : Map.of();
        if (costPerCase == null) {
            costPerCase = BigDecimal.ZERO;
        }
    }

    public static EvalMetrics aggregate(List<EvalCaseResultRecord> results) {
        if (results == null || results.isEmpty()) {
            return new EvalMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                    Map.of(), Map.of(), 0.0, BigDecimal.ZERO, 0L, 0, 0,
                    Map.of(), Map.of(), Map.of());
        }

        int totalRuns = results.size();
        long totalExactMatches = 0;
        long totalStrategyMatches = 0;
        long totalReferenceLocators = 0;
        long totalAttempts = 0;
        long totalEscalations = 0;
        long totalFirstAttemptPasses = 0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        long totalCacheReadTokens = 0;

        Map<String, Integer> histogram = new LinkedHashMap<>();
        Map<String, List<EvalCaseResultRecord>> byCase = new LinkedHashMap<>();
        List<Long> durations = new ArrayList<>(totalRuns);

        for (EvalCaseResultRecord r : results) {
            byCase.computeIfAbsent(r.caseId(), k -> new ArrayList<>()).add(r);

            if (r.firstAttemptPass()) totalFirstAttemptPasses++;
            if (r.escalated()) totalEscalations++;
            totalAttempts += r.attemptsUsed();
            totalExactMatches += r.locatorExactMatches();
            totalStrategyMatches += r.locatorStrategyMatches();
            totalReferenceLocators += r.referenceLocatorCount();

            totalInputTokens += r.inputTokens();
            totalOutputTokens += r.outputTokens();
            totalCacheReadTokens += r.cacheReadTokens();
            durations.add((long) r.durationMs());

            if (r.failureCodes() != null) {
                for (String code : r.failureCodes()) {
                    if (code != null && !code.isBlank()) {
                        histogram.put(code, histogram.getOrDefault(code, 0) + 1);
                    }
                }
            }
        }

        int totalCases = byCase.size();
        double firstAttemptPassRate = (double) totalFirstAttemptPasses / totalRuns;
        double escalationRate = (double) totalEscalations / totalRuns;
        double locatorExactMatchRate = totalReferenceLocators > 0
                ? (double) totalExactMatches / totalReferenceLocators
                : 0.0;
        double locatorStrategyMatchRate = totalReferenceLocators > 0
                ? (double) totalStrategyMatches / totalReferenceLocators
                : 0.0;
        double meanAttempts = (double) totalAttempts / totalRuns;
        double cacheReadRatio = (totalCacheReadTokens + totalInputTokens) > 0
                ? (double) totalCacheReadTokens / (totalCacheReadTokens + totalInputTokens)
                : 0.0;

        // Per-case aggregation and variance
        Map<String, Double> perCasePassRate = new LinkedHashMap<>();
        Map<String, Double> perCaseExactMatchRate = new LinkedHashMap<>();
        Map<String, Double> perCaseEscalationRate = new LinkedHashMap<>();
        Map<String, Double> perCaseVariance = new LinkedHashMap<>();

        for (Map.Entry<String, List<EvalCaseResultRecord>> entry : byCase.entrySet()) {
            String caseId = entry.getKey();
            List<EvalCaseResultRecord> caseRuns = entry.getValue();
            int k = caseRuns.size();

            long casePasses = caseRuns.stream().filter(EvalCaseResultRecord::firstAttemptPass).count();
            long caseEscalated = caseRuns.stream().filter(EvalCaseResultRecord::escalated).count();
            long caseExact = caseRuns.stream().mapToLong(EvalCaseResultRecord::locatorExactMatches).sum();
            long caseRef = caseRuns.stream().mapToLong(EvalCaseResultRecord::referenceLocatorCount).sum();

            double p = (double) casePasses / k;
            perCasePassRate.put(caseId, p);
            perCaseEscalationRate.put(caseId, (double) caseEscalated / k);
            perCaseExactMatchRate.put(caseId, caseRef > 0 ? (double) caseExact / caseRef : 0.0);

            // Variance across k repeats: Bernoulli variance p * (1 - p)
            double variance = p * (1.0 - p);
            perCaseVariance.put(caseId, variance);
        }

        // Latency p95
        Collections.sort(durations);
        long p95Duration = durations.isEmpty() ? 0 : durations.get((int) Math.min(durations.size() - 1, Math.ceil(durations.size() * 0.95) - 1));

        // Tokens & cost estimation
        double tokensPerCase = totalCases > 0 ? (double) (totalInputTokens + totalOutputTokens + totalCacheReadTokens) / totalCases : 0.0;
        BigDecimal costPerCase = BigDecimal.valueOf(tokensPerCase * 0.000003).setScale(4, RoundingMode.HALF_UP);

        return new EvalMetrics(
                firstAttemptPassRate,
                locatorExactMatchRate,
                escalationRate,
                locatorStrategyMatchRate,
                meanAttempts,
                cacheReadRatio,
                histogram,
                perCaseVariance,
                tokensPerCase,
                costPerCase,
                p95Duration,
                totalCases,
                totalRuns,
                perCasePassRate,
                perCaseExactMatchRate,
                perCaseEscalationRate
        );
    }

    public List<String> casesWithVarianceAbove(double threshold) {
        return perCaseVariance.entrySet().stream()
                .filter(e -> e.getValue() >= threshold)
                .map(Map.Entry::getKey)
                .toList();
    }

    public String toJson() {
        return Json.stringify(this);
    }

    public static EvalMetrics fromJson(String json) {
        return Json.parse(json, EvalMetrics.class);
    }
}
