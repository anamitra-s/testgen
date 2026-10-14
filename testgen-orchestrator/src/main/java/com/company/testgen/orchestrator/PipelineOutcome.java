package com.company.testgen.orchestrator;

import com.company.testgen.model.GeneratedFile;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Outcome resulting from executing the pipeline (§6.13, §9.2).
 */
public record PipelineOutcome(
        UUID jobId,
        String state,
        String confidenceTier,
        int exitCode,
        boolean escalated,
        String failureReason,
        ResolvedLocatorSet locators,
        List<GeneratedFile> files,
        long durationMs
) {
    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_ESCALATED = 10;
    public static final int EXIT_NOOP = 20;
    public static final int EXIT_PRECONDITION_FAILURE = 30;
    public static final int EXIT_INFRA_FAILURE = 40;
    public static final int EXIT_LEASE_LOST = 41;
    public static final int EXIT_BUDGET_EXCEEDED = 50;
    public static final int EXIT_REDACTION_FAILURE = 60;
    public static final int EXIT_CONFIG_ERROR = 70;

    public Map<String, ResolvedLocator> finalLocators() {
        if (locators == null || locators.locators() == null) {
            return Collections.emptyMap();
        }
        return locators.locators().stream()
                .filter(l -> l.elementRefId() != null)
                .collect(Collectors.toMap(ResolvedLocator::elementRefId, l -> l, (a, b) -> b));
    }

    public static PipelineOutcome success(UUID jobId, String confidenceTier, ResolvedLocatorSet locators, List<GeneratedFile> files, long durationMs) {
        return new PipelineOutcome(jobId, "DONE", confidenceTier, EXIT_SUCCESS, false, null, locators, files, durationMs);
    }

    public static PipelineOutcome escalated(UUID jobId, String reason, ResolvedLocatorSet locators, List<GeneratedFile> files, long durationMs) {
        return new PipelineOutcome(jobId, "NEEDS_HUMAN_REVIEW", "STATIC_ONLY_DEGRADED", EXIT_ESCALATED, true, reason, locators, files, durationMs);
    }

    public static PipelineOutcome failure(UUID jobId, String failureReason, int exitCode, long durationMs) {
        return new PipelineOutcome(jobId, "FAILED", null, exitCode, false, failureReason, new ResolvedLocatorSet(List.of()), List.of(), durationMs);
    }
}
