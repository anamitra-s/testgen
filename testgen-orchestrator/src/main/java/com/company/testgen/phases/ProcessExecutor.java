package com.company.testgen.phases;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Functional interface abstracting external process execution for L2 verification (§6.8).
 * Enables fast unit testing with mocks and robust subprocess execution in production.
 */
@FunctionalInterface
public interface ProcessExecutor {
    ProcessResult run(List<String> command, Path workingDir, Map<String, String> env, Duration timeout);
}
