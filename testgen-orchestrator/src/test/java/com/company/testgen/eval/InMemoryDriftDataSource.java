package com.company.testgen.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * In-memory test and local implementation of DriftDataSource (§8.8).
 */
public class InMemoryDriftDataSource implements DriftMonitor.DriftDataSource {

    public record RecordedJob(
            DriftMonitor.Config config,
            java.time.Instant timestamp,
            boolean firstAttemptPass,
            List<String> failureCodes
    ) {}

    private final List<RecordedJob> jobs = Collections.synchronizedList(new ArrayList<>());

    public void addJob(DriftMonitor.Config config, java.time.Instant timestamp, boolean firstAttemptPass, List<String> failureCodes) {
        jobs.add(new RecordedJob(config, timestamp, firstAttemptPass, failureCodes != null ? failureCodes : List.of()));
    }

    @Override
    public List<DriftMonitor.Config> activeConfigurations() {
        return jobs.stream()
                .map(RecordedJob::config)
                .distinct()
                .toList();
    }

    @Override
    public double firstAttemptPassRate(DriftMonitor.Config cfg, DriftMonitor.Window window) {
        List<RecordedJob> inWindow = getJobsInWindow(cfg, window);
        if (inWindow.isEmpty()) {
            return 0.0;
        }
        long passes = inWindow.stream().filter(RecordedJob::firstAttemptPass).count();
        return (double) passes / inWindow.size();
    }

    @Override
    public double ewmaFirstAttemptPassRate(DriftMonitor.Config cfg, DriftMonitor.Window window, double alpha) {
        List<RecordedJob> inWindow = getJobsInWindow(cfg, window);
        if (inWindow.isEmpty()) {
            return 0.0;
        }
        inWindow.sort(Comparator.comparing(RecordedJob::timestamp));
        List<Boolean> observations = inWindow.stream().map(RecordedJob::firstAttemptPass).toList();
        return DriftMonitor.computeEwma(observations, alpha);
    }

    @Override
    public int countIn(DriftMonitor.Config cfg, DriftMonitor.Window window) {
        return getJobsInWindow(cfg, window).size();
    }

    @Override
    public Map<String, Double> failureCodeDelta(DriftMonitor.Config cfg, DriftMonitor.Window recentWindow, DriftMonitor.Window baselineWindow) {
        List<RecordedJob> recent = getJobsInWindow(cfg, recentWindow);
        List<RecordedJob> baseline = getJobsInWindow(cfg, baselineWindow);

        Map<String, Double> recentRates = computeFailureRates(recent);
        Map<String, Double> baselineRates = computeFailureRates(baseline);

        Map<String, Double> deltas = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : recentRates.entrySet()) {
            String code = entry.getKey();
            double recRate = entry.getValue();
            double baseRate = baselineRates.getOrDefault(code, 0.0);
            deltas.put(code, recRate - baseRate);
        }
        return deltas;
    }

    private List<RecordedJob> getJobsInWindow(DriftMonitor.Config cfg, DriftMonitor.Window window) {
        return jobs.stream()
                .filter(j -> j.config().equals(cfg))
                .filter(j -> !j.timestamp().isBefore(window.start()) && !j.timestamp().isAfter(window.end()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private Map<String, Double> computeFailureRates(List<RecordedJob> list) {
        if (list.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RecordedJob j : list) {
            for (String code : j.failureCodes()) {
                counts.put(code, counts.getOrDefault(code, 0) + 1);
            }
        }
        Map<String, Double> rates = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            rates.put(e.getKey(), (double) e.getValue() / list.size());
        }
        return rates;
    }
}
