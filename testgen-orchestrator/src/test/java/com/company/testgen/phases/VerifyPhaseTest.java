package com.company.testgen.phases;

import com.company.testgen.model.GeneratedFile;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.model.VerificationResult;
import com.company.testgen.orchestrator.PipelineException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("VerifyPhase materializes GeneratedFiles into workspace before execution and restores workspace in finally (BUG-03)")
    void testMaterializesGeneratedFilesAndRestoresWorkspace() throws IOException {
        AtomicReference<Path> capturedTempSession = new AtomicReference<>();
        AuthSessionProvider sessions = new AuthSessionProvider(sessionDir) {
            @Override
            public Optional<Path> read(String targetArea) {
                Optional<Path> res = super.read(targetArea);
                res.ifPresent(capturedTempSession::set);
                return res;
            }
        };

        // Existing file that will be overwritten
        Path existingFile = workspaceDir.resolve("src/main/java/pages/LoginPage.java");
        Files.createDirectories(existingFile.getParent());
        Files.writeString(existingFile, "original Page code");

        // New file that will be created
        Path newFile = workspaceDir.resolve("src/test/java/LoginTest.java");

        List<GeneratedFile> files = List.of(
                new GeneratedFile("src/main/java/pages/LoginPage.java", "overwritten Page code", false),
                new GeneratedFile("src/test/java/LoginTest.java", "new test code", true)
        );

        AtomicBoolean verifiedDuringExecution = new AtomicBoolean(false);
        ProcessExecutor executor = (cmd, ws, env, to) -> {
            try {
                // Assert files are materialized inside workspace before maven test runs
                assertThat(Files.exists(existingFile)).isTrue();
                assertThat(Files.readString(existingFile)).isEqualTo("overwritten Page code");

                assertThat(Files.exists(newFile)).isTrue();
                assertThat(Files.readString(newFile)).isEqualTo("new test code");

                verifiedDuringExecution.set(true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return ProcessResult.success(100, "BUILD SUCCESS");
        };

        VerifyPhase verifyPhase = new VerifyPhase(sessions, Duration.ofSeconds(30), executor);
        TestGenerationJob job = createJob(false);

        VerificationResult result = verifyPhase.execute(job, files, workspaceDir);

        assertThat(result.outcome()).isEqualTo("PASSED");
        assertThat(verifiedDuringExecution.get()).isTrue();

        // Verify workspace is restored in finally
        assertThat(Files.exists(existingFile)).isTrue();
        assertThat(Files.readString(existingFile)).isEqualTo("original Page code");

        assertThat(Files.exists(newFile)).isFalse();

        // Verify temporary session file securely deleted
        assertThat(capturedTempSession.get()).isNotNull();
        assertThat(Files.exists(capturedTempSession.get())).isFalse();
    }

    @Test
    @DisplayName("VerifyPhase rejects path traversal in GeneratedFile and securely deletes session file")
    void testRejectsPathTraversalAndCleansSessionFile() {
        AtomicReference<Path> capturedTempSession = new AtomicReference<>();
        AuthSessionProvider sessions = new AuthSessionProvider(sessionDir) {
            @Override
            public Optional<Path> read(String targetArea) {
                Optional<Path> res = super.read(targetArea);
                res.ifPresent(capturedTempSession::set);
                return res;
            }
        };

        List<GeneratedFile> files = List.of(
                new GeneratedFile("../escaped.java", "malicious payload", false)
        );

        ProcessExecutor executor = (cmd, ws, env, to) -> ProcessResult.success(100, "PASS");

        VerifyPhase verifyPhase = new VerifyPhase(sessions, Duration.ofSeconds(30), executor);
        TestGenerationJob job = createJob(false);

        assertThatThrownBy(() -> verifyPhase.execute(job, files, workspaceDir))
                .isInstanceOf(PipelineException.class)
                .hasMessageContaining("Path traversal detected");

        // Temporary session file must be securely deleted in finally (NFR-17)
        assertThat(capturedTempSession.get()).isNotNull();
        assertThat(Files.exists(capturedTempSession.get())).isFalse();
    }
}
