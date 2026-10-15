package com.company.testgen.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DriftMonitorTest {

    @Test
    @DisplayName("DriftMonitor detects regression > 0.10 using 7-day EWMA vs 30-day baseline and alerts Slack (§8.8)")
    void testDriftDetectionAndAlerting() {
        InMemoryDriftDataSource dataSource = new InMemoryDriftDataSource();
        AtomicReference<String> alertSent = new AtomicReference<>();
        DriftMonitor.SlackAlerter slack = alertSent::set;

        DriftMonitor.Config config = new DriftMonitor.Config("claude-sonnet-5", "abc123456789");
        Instant now = Instant.now();

        // 30-day baseline window (ending 7 days ago): 30 jobs, 90% pass rate (27 pass, 3 fail)
        for (int i = 0; i < 30; i++) {
            Instant ts = now.minus(Duration.ofDays(10 + (i % 15)));
            boolean pass = i < 27;
            List<String> codes = pass ? List.of() : List.of("NOT_UNIQUE");
            dataSource.addJob(config, ts, pass, codes);
        }

        // Recent 7-day window: 25 jobs (n >= 20), pass rate drops to 60%
        // with NOT_UNIQUE and BANNED_PATTERN errors
        for (int i = 0; i < 25; i++) {
            Instant ts = now.minus(Duration.ofDays(1 + (i % 5)));
            boolean pass = i < 15; // 60% pass rate
            List<String> codes = pass ? List.of() : List.of("NOT_UNIQUE", "BANNED_PATTERN");
            dataSource.addJob(config, ts, pass, codes);
        }

        DriftMonitor monitor = new DriftMonitor(dataSource, slack);
        monitor.check();

        assertThat(alertSent.get()).isNotNull();
        assertThat(alertSent.get()).contains("Production quality drift");
        assertThat(alertSent.get()).contains("claude-sonnet-5 / prompts abc12345");
        assertThat(alertSent.get()).contains("first_attempt_pass_rate:");
        assertThat(alertSent.get()).contains("NOT_UNIQUE");
    }

    @Test
    @DisplayName("DriftMonitor stays silent when sample size in 7-day window is n < 20 (§8.8)")
    void testDriftMonitorStaysSilentOnLowVolume() {
        InMemoryDriftDataSource dataSource = new InMemoryDriftDataSource();
        AtomicReference<String> alertSent = new AtomicReference<>();
        DriftMonitor.SlackAlerter slack = alertSent::set;

        DriftMonitor.Config config = new DriftMonitor.Config("claude-sonnet-5", "abc123456789");
        Instant now = Instant.now();

        // Baseline: 90% pass rate
        for (int i = 0; i < 30; i++) {
            Instant ts = now.minus(Duration.ofDays(12));
            dataSource.addJob(config, ts, true, List.of());
        }

        // Recent window: only 10 jobs (n < 20), even though 100% failed!
        for (int i = 0; i < 10; i++) {
            Instant ts = now.minus(Duration.ofDays(2));
            dataSource.addJob(config, ts, false, List.of("TIMEOUT"));
        }

        DriftMonitor monitor = new DriftMonitor(dataSource, slack);
        monitor.check();

        assertThat(alertSent.get()).isNull(); // Guard n >= 20 prevented false alarm
    }

    @Test
    @DisplayName("EWMA calculation matches alpha = 0.2 exponential smoothing (§8.8)")
    void testEwmaCalculation() {
        List<Boolean> observations = List.of(true, false, false, true);
        // t0: 1.0
        // t1: 0.2 * 0.0 + 0.8 * 1.0 = 0.8
        // t2: 0.2 * 0.0 + 0.8 * 0.8 = 0.64
        // t3: 0.2 * 1.0 + 0.8 * 0.64 = 0.2 + 0.512 = 0.712
        double ewma = DriftMonitor.computeEwma(observations, 0.2);
        assertThat(ewma).isCloseTo(0.712, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("top3 formats highest failure code deltas properly")
    void testTop3Formatting() {
        Map<String, Double> deltas = Map.of(
                "CODE_A", 0.15,
                "CODE_B", 0.08,
                "CODE_C", 0.03,
                "CODE_D", 0.01
        );

        String top = DriftMonitor.top3(deltas);
        assertThat(top).contains("CODE_A (+15.0%)");
        assertThat(top).contains("CODE_B (+8.0%)");
        assertThat(top).contains("CODE_C (+3.0%)");
        assertThat(top).doesNotContain("CODE_D");
    }
}
