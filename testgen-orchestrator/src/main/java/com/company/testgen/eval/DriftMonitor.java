package com.company.testgen.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Production Drift Monitor (§8.8).
 * Catches quality regressions caused by vendor-side LLM changes or target-app drift.
 * Computes 7-day EWMA (ALPHA = 0.2) of first_attempt_pass_rate against 30-day baseline
 * per (model_id, prompt_bundle_hash) and alerts Slack if regression exceeds 0.10 (n >= 20).
 */
public class DriftMonitor {

    private static final Logger log = LoggerFactory.getLogger(DriftMonitor.class);

    public static final double ALPHA = 0.2;         // EWMA smoothing (§8.8)
    public static final double ALERT_DELTA = 0.10;  // 10 percentage points regression (§8.8)
    public static final int MIN_SAMPLE_SIZE = 20;   // Minimum observations in 7-day window (§8.8)

    public record Config(String modelId, String promptBundleHash) {
        public String promptHash() {
            return promptBundleHash != null ? promptBundleHash : "";
        }
    }

    public record Window(Instant start, Instant end) {
        public static Window days(int days) {
            Instant now = Instant.now();
            return new Window(now.minus(Duration.ofDays(days)), now);
        }

        public Window endingDaysAgo(int daysAgo) {
            Instant newEnd = Instant.now().minus(Duration.ofDays(daysAgo));
            Duration length = Duration.between(start, end);
            return new Window(newEnd.minus(length), newEnd);
        }
    }

    public record JobEvent(Instant timestamp, boolean firstAttemptPass, List<String> failureCodes) {}

    @FunctionalInterface
    public interface SlackAlerter {
        void alert(String message);
    }

    public interface DriftDataSource {
        List<Config> activeConfigurations();
        double firstAttemptPassRate(Config cfg, Window window);
        double ewmaFirstAttemptPassRate(Config cfg, Window window, double alpha);
        int countIn(Config cfg, Window window);
        Map<String, Double> failureCodeDelta(Config cfg, Window recentWindow, Window baselineWindow);
    }

    private final DriftDataSource dataSource;
    private final SlackAlerter slack;
    private final double alpha;
    private final double alertDelta;
    private final int minSampleSize;

    public DriftMonitor(DriftDataSource dataSource, SlackAlerter slack, double alpha, double alertDelta, int minSampleSize) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource cannot be null");
        this.slack = slack != null ? slack : msg -> log.warn("SlackAlert: {}", msg);
        this.alpha = alpha;
        this.alertDelta = alertDelta;
        this.minSampleSize = minSampleSize;
    }

    public DriftMonitor(DriftDataSource dataSource, SlackAlerter slack) {
        this(dataSource, slack, ALPHA, ALERT_DELTA, MIN_SAMPLE_SIZE);
    }

    /**
     * Runs daily drift sweep across active model/prompt configurations (§8.8).
     */
    public void check() {
        log.info("Starting Production Drift Monitor sweep...");
        List<Config> configs = dataSource.activeConfigurations();

        for (Config cfg : configs) {
            Window baselineWindow = Window.days(30).endingDaysAgo(7);
            Window recentWindow = Window.days(7);

            double baseline = dataSource.firstAttemptPassRate(cfg, baselineWindow);
            double current = dataSource.ewmaFirstAttemptPassRate(cfg, recentWindow, alpha);
            int n = dataSource.countIn(cfg, recentWindow);

            if (n < minSampleSize) {
                log.debug("Config (model={}, prompt={}): n={} < {} — skipping to avoid false alerts",
                        cfg.modelId(), cfg.promptHash(), n, minSampleSize);
                continue;
            }

            double regression = baseline - current;
            if (regression > alertDelta) {
                Map<String, Double> codeDelta = dataSource.failureCodeDelta(cfg, recentWindow, baselineWindow);
                String promptShort = cfg.promptHash().length() >= 8 ? cfg.promptHash().substring(0, 8) : cfg.promptHash();
                String message = String.format(
                        "⚠️ Production quality drift — %s / prompts %s\n" +
                        "first_attempt_pass_rate: %.1f%% → %.1f%% (n=%d, 7d)\n" +
                        "Shift by reason code: %s\n" +
                        "No deployment in window. Suspect vendor-side change or app drift.",
                        cfg.modelId(), promptShort,
                        baseline * 100.0, current * 100.0, n,
                        top3(codeDelta)
                );
                log.warn("Drift detected: {}", message);
                slack.alert(message);
            }
        }
    }

    /**
     * Computes the EWMA (Exponentially Weighted Moving Average) of a binary pass/fail series.
     */
    public static double computeEwma(List<Boolean> observations, double alpha) {
        if (observations == null || observations.isEmpty()) {
            return 0.0;
        }
        double ewma = observations.get(0) ? 1.0 : 0.0;
        for (int i = 1; i < observations.size(); i++) {
            double value = observations.get(i) ? 1.0 : 0.0;
            ewma = alpha * value + (1.0 - alpha) * ewma;
        }
        return ewma;
    }

    /**
     * Formats the top 3 reason codes with highest positive delta (§8.8).
     */
    public static String top3(Map<String, Double> codeDelta) {
        if (codeDelta == null || codeDelta.isEmpty()) {
            return "none";
        }
        return codeDelta.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(3)
                .map(e -> String.format("%s (+%.1f%%)", e.getKey(), e.getValue() * 100.0))
                .collect(Collectors.joining(", "));
    }

    public DriftDataSource dataSource() {
        return dataSource;
    }

    public SlackAlerter slack() {
        return slack;
    }
}
