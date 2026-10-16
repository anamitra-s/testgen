package com.company.testgen.phases;

import com.company.testgen.model.GeneratedFile;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.model.VerificationResult;
import com.company.testgen.orchestrator.PipelineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * L2 Dynamic Verification Phase (§6.8).
 * Executes the generated test against the target environment using read-only session tokens.
 * Fails open (SKIPPED) on mutatesData(), NO_SESSION, TIMEOUT, or ENV_UNAVAILABLE.
 * Retries once on test failure to distinguish flake from genuine breakage (PASSED_AFTER_FLAKE vs FAILED).
 * Always securely deletes the session file in finally.
 */
public class VerifyPhase {

    private static final Logger log = LoggerFactory.getLogger(VerifyPhase.class);

    private final AuthSessionProvider sessions;   // READ-ONLY: never performs a login
    private final Duration timeout;
    private final ProcessExecutor processExecutor;

    public VerifyPhase(AuthSessionProvider sessions, Duration timeout) {
        this(sessions, timeout, null);
    }

    public VerifyPhase(AuthSessionProvider sessions, Duration timeout, ProcessExecutor processExecutor) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.timeout = timeout != null ? timeout : Duration.ofMinutes(5);
        this.processExecutor = processExecutor;
    }

    public VerificationResult execute(TestGenerationJob job, List<GeneratedFile> files, Path workspace) {
        Objects.requireNonNull(job, "job must not be null");

        // Fail-open: Skip mutating tests (§6.8)
        if (job.plan() != null && job.plan().mutatesData()) {
            log.info("L2 verification skipped: test plan mutates data");
            return VerificationResult.skipped("MUTATING_TEST");
        }

        // Fail-open: Skip if no pre-minted session token is available (§6.8)
        Optional<Path> storageState = sessions.read(job.targetArea());
        if (storageState.isEmpty()) {
            log.info("L2 verification skipped: no auth session available for targetArea '{}'", job.targetArea());
            return VerificationResult.skipped("NO_SESSION");
        }

        Map<Path, byte[]> originalFiles = new LinkedHashMap<>();
        List<Path> createdFiles = new ArrayList<>();

        try {
            if (files != null && workspace != null) {
                Path absWorkspace = workspace.toAbsolutePath().normalize();

                // 1. Validate every GeneratedFile.path() to prevent path traversal
                for (GeneratedFile file : files) {
                    if (file == null || file.path() == null) {
                        continue;
                    }
                    Path target = workspace.resolve(file.path()).normalize().toAbsolutePath();
                    if (!target.startsWith(absWorkspace)) {
                        throw new PipelineException("Path traversal detected in generated file path: " + file.path());
                    }
                }

                // 2. Snapshot original file contents or track newly created files, then write content
                for (GeneratedFile file : files) {
                    if (file == null || file.path() == null || file.content() == null) {
                        continue;
                    }
                    Path target = workspace.resolve(file.path()).normalize().toAbsolutePath();
                    try {
                        if (Files.exists(target)) {
                            if (!originalFiles.containsKey(target)) {
                                originalFiles.put(target, Files.readAllBytes(target));
                            }
                        } else {
                            if (!createdFiles.contains(target)) {
                                createdFiles.add(target);
                            }
                        }

                        // 3. Create parent directories and write file.content() (UTF-8) to target
                        if (target.getParent() != null) {
                            Files.createDirectories(target.getParent());
                        }
                        Files.writeString(target, file.content(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new PipelineException("Failed to write generated file " + file.path() + " to workspace", e);
                    }
                }
            }

            ProcessResult first = runTest(job, workspace, storageState.get());
            if (first.exitCode() == 0) {
                log.info("L2 verification passed on first run in {}ms", first.durationMs());
                return VerificationResult.passed(first.durationMs());
            }
            if (first.timedOut()) {
                log.warn("L2 verification timed out after {}ms", first.durationMs());
                return VerificationResult.skipped("TIMEOUT");
            }
            if (first.isInfraFailure()) {
                log.warn("L2 verification encountered infrastructure failure: {}", first.tail(20));
                return VerificationResult.skipped("ENV_UNAVAILABLE");
            }

            // One retry distinguishes genuine breakage from environmental flake (§6.8)
            log.warn("L2 verification failed on first run with exit code {}. Retrying once to distinguish flake...",
                    first.exitCode());
            ProcessResult second = runTest(job, workspace, storageState.get());
            if (second.exitCode() == 0) {
                log.info("L2 verification passed on retry (PASSED_AFTER_FLAKE) in {}ms", second.durationMs());
                return VerificationResult.passedAfterFlake(second.durationMs());
            }
            if (second.timedOut()) {
                log.warn("L2 retry timed out");
                return VerificationResult.skipped("TIMEOUT");
            }
            if (second.isInfraFailure()) {
                log.warn("L2 retry encountered infrastructure failure: {}", second.tail(20));
                return VerificationResult.skipped("ENV_UNAVAILABLE");
            }

            log.warn("L2 verification confirmed test failure after retry. Tail:\n{}", first.tail(200));
            return VerificationResult.failed(first.tail(200));

        } finally {
            // Restore workspace: delete newly created files, revert overwritten files
            for (Path created : createdFiles) {
                try {
                    Files.deleteIfExists(created);
                } catch (IOException e) {
                    log.warn("Failed to delete created file {}: {}", created, e.getMessage());
                }
            }
            for (Map.Entry<Path, byte[]> entry : originalFiles.entrySet()) {
                try {
                    Files.write(entry.getKey(), entry.getValue());
                } catch (IOException e) {
                    log.warn("Failed to restore original file {}: {}", entry.getKey(), e.getMessage());
                }
            }

            SecureDelete.of(storageState.get());   // token material never outlives the phase
        }
    }

    private ProcessResult runTest(TestGenerationJob job, Path ws, Path state) {
        String testClass = job.generatedTestClassName();
        String testEnv = job.targetEnvironment() != null ? job.targetEnvironment() : "staging";

        if (processExecutor != null) {
            List<String> command = List.of(
                    "mvn", "-q", "test",
                    "-Dtest=" + testClass,
                    "-DfailIfNoTests=true"
            );
            Map<String, String> env = Map.of(
                    "PW_STORAGE_STATE", state.toString(),
                    "TEST_ENV", testEnv
            );
            return processExecutor.run(command, ws, env, timeout);
        }

        return Processes.exec("mvn", "-q", "test",
                        "-Dtest=" + testClass,
                        "-DfailIfNoTests=true")
                .workingDir(ws)
                .env("PW_STORAGE_STATE", state.toString())
                .env("TEST_ENV", testEnv)
                .timeout(timeout)
                .run();
    }
}
