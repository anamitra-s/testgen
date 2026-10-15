package com.company.testgen.eval;

import java.nio.file.Path;

/**
 * Configuration options for an evaluation run (§8.5).
 */
public record RunConfig(
        int concurrency,
        int repeats,
        String gate,
        String trigger,
        String modelId,
        String promptBundleHash,
        String orchestratorVersion,
        String gitRef,
        Path corpusDir,
        Path reportPath
) {
    public static final String GATE_BLOCKING = "blocking";
    public static final String GATE_ADVISORY = "advisory";

    public RunConfig {
        concurrency = concurrency > 0 ? concurrency : 4;
        repeats = repeats > 0 ? repeats : 1;
        gate = gate != null ? gate.toLowerCase() : GATE_ADVISORY;
        trigger = trigger != null ? trigger : "MANUAL";
        modelId = modelId != null ? modelId : "claude-sonnet-5";
        promptBundleHash = promptBundleHash != null ? promptBundleHash : "default-bundle";
        orchestratorVersion = orchestratorVersion != null ? orchestratorVersion : "1.0.0";
        gitRef = gitRef != null ? gitRef : "main";
        corpusDir = corpusDir != null ? corpusDir : Path.of("evals");
        reportPath = reportPath != null ? reportPath : Path.of("eval-report.json");
    }

    public boolean isBlocking() {
        return GATE_BLOCKING.equalsIgnoreCase(gate);
    }

    public static RunConfig defaults() {
        return new RunConfig(4, 1, GATE_ADVISORY, "MANUAL", "claude-sonnet-5", "default-bundle", "1.0.0", "main", Path.of("evals"), Path.of("eval-report.json"));
    }
}
