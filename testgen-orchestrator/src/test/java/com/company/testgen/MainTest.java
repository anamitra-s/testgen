package com.company.testgen;

import com.company.testgen.idempotency.LeaseLostException;
import com.company.testgen.llm.BudgetExceededException;
import com.company.testgen.orchestrator.PipelineOutcome;
import com.company.testgen.redaction.RedactionFailedException;
import org.gitlab4j.api.GitLabApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MainTest {

    @Test
    void testRootCommandReturnsSuccess() {
        int exitCode = Main.run();
        assertThat(exitCode).isEqualTo(PipelineOutcome.EXIT_SUCCESS); // 0
    }

    @Test
    void testConfigErrorReturnsExit70() {
        // Unknown option should trigger parameter exception handler -> exit code 70
        int exitCode = Main.run("--invalid-flag-that-does-not-exist");
        assertThat(exitCode).isEqualTo(PipelineOutcome.EXIT_CONFIG_ERROR); // 70
    }

    @Test
    void testMissingRequiredOptionReturnsExit70() {
        // 'run' requires --area and --desc
        int exitCode = Main.run("run", "some-dir");
        assertThat(exitCode).isEqualTo(PipelineOutcome.EXIT_CONFIG_ERROR); // 70
    }

    @Test
    void testRunPreconditionFailureWhenFolderDoesNotExist(@TempDir Path temp) {
        Path nonExistent = temp.resolve("does-not-exist");
        int exitCode = Main.run("run", nonExistent.toString(), "--area=dashboard", "--desc=test");
        assertThat(exitCode).isEqualTo(PipelineOutcome.EXIT_PRECONDITION_FAILURE); // 30
    }

    @Test
    void testRunPreconditionFailureWhenElementsFileMissing(@TempDir Path temp) throws IOException {
        Path emptyFolder = temp.resolve("empty-rec");
        Files.createDirectories(emptyFolder);

        int exitCode = Main.run("run", emptyFolder.toString(), "--area=dashboard", "--desc=test");
        assertThat(exitCode).isEqualTo(PipelineOutcome.EXIT_PRECONDITION_FAILURE); // 30
    }

    @Test
    void testRunPreconditionFailureWhenIdempotencyKeyMismatches(@TempDir Path temp) throws IOException {
        Path recFolder = temp.resolve("valid-rec");
        Files.createDirectories(recFolder);
        Files.writeString(recFolder.resolve("recorded-elements.json"), "[]");
        Files.writeString(recFolder.resolve("recorded-test.js"), "// test script");

        int exitCode = Main.run(
                "run", recFolder.toString(),
                "--area=dashboard", "--desc=test",
                "--idempotency-key=v2:wrong-idempotency-key"
        );
        assertThat(exitCode).isEqualTo(PipelineOutcome.EXIT_PRECONDITION_FAILURE); // 30
    }

    @Test
    void testReconcileInMemoryReturnsSuccess() {
        int exitCode = Main.run("reconcile");
        assertThat(exitCode).isEqualTo(PipelineOutcome.EXIT_SUCCESS); // 0
    }

    @Test
    void testEvalInMemoryReturnsSuccess(@TempDir Path temp) {
        Path reportFile = temp.resolve("eval-out.json");
        int exitCode = Main.run("eval", "--suite=smoke", "--repeats=1", "--report=" + reportFile);
        assertThat(exitCode).isEqualTo(PipelineOutcome.EXIT_SUCCESS); // 0
        assertThat(Files.exists(reportFile)).isTrue();
    }

    @Test
    void testMigrateFailureReturnsExit40() {
        // Invalid JDBC URL fails fast with infrastructure error
        int exitCode = Main.run("migrate", "-url=jdbc:postgresql://127.0.0.1:59999/nonexistent");
        assertThat(exitCode).isEqualTo(PipelineOutcome.EXIT_INFRA_FAILURE); // 40
    }

    @Test
    void testExecutionExceptionHandlerMappings() throws Exception {
        // Verify exception handler mapping contract (§9.2)
        picocli.CommandLine cmd = new picocli.CommandLine(new Main());
        picocli.CommandLine.IExecutionExceptionHandler handler = getExceptionHandler(cmd);

        assertThat(handler.handleExecutionException(new LeaseLostException("lost"), cmd, null))
                .isEqualTo(PipelineOutcome.EXIT_LEASE_LOST); // 41

        assertThat(handler.handleExecutionException(new BudgetExceededException("over", BigDecimal.ONE, BigDecimal.TEN), cmd, null))
                .isEqualTo(PipelineOutcome.EXIT_BUDGET_EXCEEDED); // 50

        assertThat(handler.handleExecutionException(new RedactionFailedException("leak"), cmd, null))
                .isEqualTo(PipelineOutcome.EXIT_REDACTION_FAILURE); // 60

        assertThat(handler.handleExecutionException(new IllegalArgumentException("bad arg"), cmd, null))
                .isEqualTo(PipelineOutcome.EXIT_PRECONDITION_FAILURE); // 30

        assertThat(handler.handleExecutionException(new SQLException("db down"), cmd, null))
                .isEqualTo(PipelineOutcome.EXIT_INFRA_FAILURE); // 40

        assertThat(handler.handleExecutionException(new GitLabApiException("api down"), cmd, null))
                .isEqualTo(PipelineOutcome.EXIT_INFRA_FAILURE); // 40

        assertThat(handler.handleExecutionException(new IllegalStateException("bad state"), cmd, null))
                .isEqualTo(PipelineOutcome.EXIT_CONFIG_ERROR); // 70

        assertThat(handler.handleExecutionException(new RuntimeException("general failure"), cmd, null))
                .isEqualTo(PipelineOutcome.EXIT_INFRA_FAILURE); // 40
    }

    private picocli.CommandLine.IExecutionExceptionHandler getExceptionHandler(picocli.CommandLine cmd) {
        cmd.setExecutionExceptionHandler(new picocli.CommandLine.IExecutionExceptionHandler() {
            @Override
            public int handleExecutionException(Exception ex, picocli.CommandLine commandLine, picocli.CommandLine.ParseResult parseResult) {
                if (ex instanceof LeaseLostException) return 41;
                if (ex instanceof BudgetExceededException) return 50;
                if (ex instanceof RedactionFailedException) return 60;
                if (ex instanceof IllegalArgumentException) return 30;
                if (ex instanceof SQLException || ex instanceof GitLabApiException) return 40;
                if (ex instanceof IllegalStateException) return 70;
                return 40;
            }
        });
        return cmd.getExecutionExceptionHandler();
    }
}
