package com.company.testgen.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Specification for an evaluation suite defined in {@code suites.yaml} (§8.2).
 */
public record SuiteSpec(
        String name,
        String cases,
        String selection,
        int repeats,
        String filter
) {

    private static final Pattern DIFFICULTY_PATTERN = Pattern.compile("difficulty\\s*==\\s*([a-zA-Z]+)", Pattern.CASE_INSENSITIVE);

    public SuiteSpec {
        name = name != null ? name : "default";
        repeats = repeats > 0 ? repeats : 1;
    }

    public static SuiteSpec smoke() {
        return new SuiteSpec("smoke", "10", "stratified", 1, null);
    }

    public static SuiteSpec golden() {
        return new SuiteSpec("golden", "all", "all", 3, null);
    }

    public static SuiteSpec hard() {
        return new SuiteSpec("hard", "all", "all", 5, "difficulty == hard");
    }

    /**
     * Determines whether the given evaluation case matches the suite filter criteria.
     */
    public boolean matches(EvalCase c) {
        if (c == null) {
            return false;
        }

        // Exclude quarantined and retired cases from standard suites unless filter specifically allows them
        if (filter == null || !filter.toLowerCase(Locale.ROOT).contains("status")) {
            if (!c.isActive()) {
                return false;
            }
        }

        if (filter == null || filter.isBlank()) {
            return true;
        }

        Matcher diffMatcher = DIFFICULTY_PATTERN.matcher(filter);
        if (diffMatcher.find()) {
            String requiredDifficulty = diffMatcher.group(1);
            if (!requiredDifficulty.equalsIgnoreCase(c.difficulty())) {
                return false;
            }
        }

        return true;
    }

    /**
     * Filters and selects cases according to this suite's selection criteria (§8.2).
     * Clamps selection to available active matching cases without throwing exceptions.
     */
    public List<EvalCase> filterCases(List<EvalCase> allCases) {
        if (allCases == null || allCases.isEmpty()) {
            return List.of();
        }

        List<EvalCase> matched = allCases.stream()
                .filter(this::matches)
                .filter(EvalCase::isActive)
                .toList();

        if (cases == null || "all".equalsIgnoreCase(cases)) {
            return matched;
        }

        int targetCount;
        try {
            targetCount = Integer.parseInt(cases.trim());
        } catch (NumberFormatException e) {
            return matched;
        }

        if (targetCount <= 0) {
            return List.of();
        }

        int clampedCount = Math.min(targetCount, matched.size());
        if (matched.size() <= clampedCount) {
            return matched;
        }

        if ("stratified".equalsIgnoreCase(selection)) {
            return stratifiedSample(matched, clampedCount);
        }

        return matched.subList(0, clampedCount);
    }

    private List<EvalCase> stratifiedSample(List<EvalCase> input, int count) {
        if (input == null || input.isEmpty() || count <= 0) {
            return List.of();
        }
        int effectiveCount = Math.min(count, input.size());
        // Stratify by (difficulty, targetArea)
        Map<String, List<EvalCase>> strata = new LinkedHashMap<>();
        for (EvalCase c : input) {
            String key = c.difficulty() + ":" + c.targetArea();
            strata.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }

        List<EvalCase> result = new ArrayList<>(effectiveCount);
        boolean added = true;
        while (result.size() < effectiveCount && added) {
            added = false;
            for (List<EvalCase> stratum : strata.values()) {
                if (!stratum.isEmpty() && result.size() < effectiveCount) {
                    result.add(stratum.remove(0));
                    added = true;
                }
            }
        }
        return Collections.unmodifiableList(result);
    }
}
