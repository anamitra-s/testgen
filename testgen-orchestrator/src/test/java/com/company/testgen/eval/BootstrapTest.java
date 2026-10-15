package com.company.testgen.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Unit tests for Bootstrap resampling (§8.4, §9.5).
 * Tests difference confidence interval (candidate - baseline) across cases.
 */
class BootstrapTest {

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

        assertThat(ci.point()).isCloseTo(0.0, offset(0.0001));
        assertThat(ci.lower()).isCloseTo(0.0, offset(0.0001));
        assertThat(ci.upper()).isCloseTo(0.0, offset(0.0001));
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
}
