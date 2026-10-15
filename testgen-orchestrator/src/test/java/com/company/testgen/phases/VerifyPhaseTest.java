package com.company.testgen.phases;

import com.company.testgen.model.GeneratedFile;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.model.VerificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class VerifyPhaseTest {

    @TempDir
    Path tempDir;

    private Path sessionDir;
    private Path workspaceDir;
    private Path sessionFile;

    @BeforeEach
    void setUp() throws IOException {
        sessionDir = tempDir.resolve("sessions");
        workspaceDir = tempDir.resolve("workspace");
        Files.createDirectories(sessionDir);
        Files.createDirectories(workspaceDir);

        sessionFile = sessionDir.resolve("billing.json");
        Files.writeString(sessionFile, "{\"cookies\": [\"token=123\"]}");
    }

    private TestGenerationJob createJob(boolean mutatesData) {
        ParsedTestPlan plan = new ParsedTestPlan("billing", "Billing test", List.of(), List.of(), false, mutatesData);
        return new TestGenerationJob(
                UUID.randomUUID(),
                "idemp-1",
                "billing",
                "Billing test",
                "jenkins",
                "main",
                "hash",
                "claude-sonnet-5",
                "1.0",
                "1.0",
                "sha-1",
                "agent-1",
                1L,
                tempDir,
                plan,
                "staging"
        );
    }

    @Test
    @DisplayName("VerifyPhase fails open (SKIPPED MUTATING_TEST) when job mutates data")
    void testSkipsMutatingTests() {
        AuthSessionProvider sessions = new AuthSessionProvider(sessionDir);
        AtomicInteger runCount = new AtomicInteger(0);
        ProcessExecutor executor = (cmd, ws, env, to) -> {
            runCount.incrementAndGet();
            return ProcessResult.success(100, "PASS");
        };

        VerifyPhase verifyPhase = new VerifyPhase(sessions, Duration.ofSeconds(30), executor);
        TestGenerationJob mutatingJob = createJob(true);

        VerificationResult result = verifyPhase.execute(mutatingJob, List.of(), workspaceDir);

        assertThat(result.isSkipped()).isTrue();
        assertThat(result.skipReason()).isEqualTo("MUTATING_TEST");
        assertThat(runCount.get()).isZero();
    }

    @Test
    @DisplayName("VerifyPhase fails open (SKIPPED NO_SESSION) when auth session is not found")
    void testSkipsWhenNoSession() {
        Path emptySessionDir = tempDir.resolve("empty_sessions");
        AuthSessionProvider sessions = new AuthSessionProvider(emptySessionDir);
        AtomicInteger runCount = new AtomicInteger(0);
        ProcessExecutor executor = (cmd, ws, env, to) -> {
            runCount.incrementAndGet();
            return ProcessResult.success(100, "PASS");
        };

        VerifyPhase verifyPhase = new VerifyPhase(sessions, Duration.ofSeconds(30), executor);
        TestGenerationJob job = createJob(false);

        VerificationResult result = verifyPhase.execute(job, List.of(), workspaceDir);

        assertThat(result.isSkipped()).isTrue();
        assertThat(result.skipReason()).isEqualTo("NO_SESSION");
        assertThat(runCount.get()).isZero();
    }

    @Test
    @DisplayName("VerifyPhase returns PASSED on clean first test execution")
    void testPassedOnFirstRun() {
        AtomicReference<Path> capturedSessionPath = new AtomicReference<>();
        AuthSessionProvider sessions = new AuthSessionProvider(sessionDir);

        ProcessExecutor executor = (cmd, ws, env, to) -> {
            capturedSessionPath.set(Path.of(env.get("PW_STORAGE_STATE")));
            // Session file should exist during execution
            assertThat(Files.exists(capturedSessionPath.get())).isTrue();
            return ProcessResult.success(1500, "BUILD SUCCESS\nTests run: 1, Failures: 0");
        };

        VerifyPhase verifyPhase = new VerifyPhase(sessions, Duration.ofSeconds(30), executor);
        TestGenerationJob job = createJob(false);

        VerificationResult result = verifyPhase.execute(job, List.of(), workspaceDir);

        assertThat(result.outcome()).isEqualTo("PASSED");
        assertThat(result.durationMs()).isEqualTo(1500);

        // Verify token file securely deleted in finally (§6.8)
        assertThat(Files.exists(capturedSessionPath.get())).isFalse();
    }

    @Test
    @DisplayName("VerifyPhase fails open (SKIPPED TIMEOUT) when process times out")
    void testSkipsOnTimeout() {
        AtomicReference<Path> capturedSessionPath = new AtomicReference<>();
        AuthSessionProvider sessions = new AuthSessionProvider(sessionDir);

        ProcessExecutor executor = (cmd, ws, env, to) -> {
            capturedSessionPath.set(Path.of(env.get("PW_STORAGE_STATE")));
            return ProcessResult.timedOut(5000);
        };

        VerifyPhase verifyPhase = new VerifyPhase(sessions, Duration.ofSeconds(5), executor);
        TestGenerationJob job = createJob(false);

        VerificationResult result = verifyPhase.execute(job, List.of(), workspaceDir);

        assertThat(result.isSkipped()).isTrue();
        assertThat(result.skipReason()).isEqualTo("TIMEOUT");

        // Token file securely deleted
        assertThat(Files.exists(capturedSessionPath.get())).isFalse();
    }

    @Test
    @DisplayName("VerifyPhase fails open (SKIPPED ENV_UNAVAILABLE) on infrastructure failure")
    void testSkipsOnInfraFailure() {
        AtomicReference<Path> capturedSessionPath = new AtomicReference<>();
        AuthSessionProvider sessions = new AuthSessionProvider(sessionDir);

        ProcessExecutor executor = (cmd, ws, env, to) -> {
            capturedSessionPath.set(Path.of(env.get("PW_STORAGE_STATE")));
            return ProcessResult.infraFailure(200, "Connection refused: staging.internal.net:443");
        };

        VerifyPhase verifyPhase = new VerifyPhase(sessions, Duration.ofSeconds(30), executor);
        TestGenerationJob job = createJob(false);

        VerificationResult result = verifyPhase.execute(job, List.of(), workspaceDir);

        assertThat(result.isSkipped()).isTrue();
        assertThat(result.skipReason()).isEqualTo("ENV_UNAVAILABLE");

        // Token file securely deleted
        assertThat(Files.exists(capturedSessionPath.get())).isFalse();
    }

    @Test
    @DisplayName("VerifyPhase retries once on failure and distinguishes flake (PASSED_AFTER_FLAKE)")
    void testPassedAfterFlake() {
        AtomicReference<Path> capturedSessionPath = new AtomicReference<>();
        AuthSessionProvider sessions = new AuthSessionProvider(sessionDir);
        AtomicInteger attempts = new AtomicInteger(0);

        ProcessExecutor executor = (cmd, ws, env, to) -> {
            capturedSessionPath.set(Path.of(env.get("PW_STORAGE_STATE")));
            if (attempts.incrementAndGet() == 1) {
                // First run fails due to flake
                return ProcessResult.failure(1, 1200, "AssertionError: element not attached yet", "");
            } else {
                // Second run passes
                return ProcessResult.success(1400, "BUILD SUCCESS\nTests run: 1, Failures: 0");
            }
        };

        VerifyPhase verifyPhase = new VerifyPhase(sessions, Duration.ofSeconds(30), executor);
        TestGenerationJob job = createJob(false);

        VerificationResult result = verifyPhase.execute(job, List.of(), workspaceDir);

        assertThat(result.outcome()).isEqualTo("PASSED_AFTER_FLAKE");
        assertThat(result.durationMs()).isEqualTo(1400);
        assertThat(attempts.get()).isEqualTo(2);

        // Token file securely deleted
        assertThat(Files.exists(capturedSessionPath.get())).isFalse();
    }

    @Test
    @DisplayName("VerifyPhase confirms genuine failure when retry also fails (FAILED)")
    void testConfirmedFailure() {
        AtomicReference<Path> capturedSessionPath = new AtomicReference<>();
        AuthSessionProvider sessions = new AuthSessionProvider(sessionDir);
        AtomicInteger attempts = new AtomicInteger(0);

        ProcessExecutor executor = (cmd, ws, env, to) -> {
            capturedSessionPath.set(Path.of(env.get("PW_STORAGE_STATE")));
            attempts.incrementAndGet();
            return ProcessResult.failure(1, 1100, "Error: Locator not found\nStack trace line 1\nStack trace line 2", "");
        };

        VerifyPhase verifyPhase = new VerifyPhase(sessions, Duration.ofSeconds(30), executor);
        TestGenerationJob job = createJob(false);

        VerificationResult result = verifyPhase.execute(job, List.of(), workspaceDir);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.outcome()).isEqualTo("FAILED");
        assertThat(result.failureTail()).contains("Error: Locator not found");
        assertThat(attempts.get()).isEqualTo(2);

        // Token file securely deleted
        assertThat(Files.exists(capturedSessionPath.get())).isFalse();
    }
}
