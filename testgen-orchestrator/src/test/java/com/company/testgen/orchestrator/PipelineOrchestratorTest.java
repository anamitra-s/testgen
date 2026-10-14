package com.company.testgen.orchestrator;

import com.company.testgen.model.DomElementSnapshot;
import com.company.testgen.model.JobRecord;
import com.company.testgen.model.PageSnapshot;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.RecordedAction;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.observability.JobSummary;
import com.company.testgen.observability.JobSummaryWriter;
import com.company.testgen.persistence.InMemoryJobRepository;
import com.company.testgen.persistence.JobRepository;
import com.company.testgen.phase.ContextAssemblyPhase;
import com.company.testgen.phase.ParsePhase;
import com.company.testgen.phase.RedactionPhase;
import com.company.testgen.redaction.DomRedactor;
import com.company.testgen.redaction.RedactionFailedException;
import com.company.testgen.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineOrchestratorTest {

    @TempDir
    Path tempDir;

    private JobRepository jobRepository;
    private JobSummaryWriter summaryWriter;
    private ParsePhase parsePhase;
    private RedactionPhase redactionPhase;
    private ContextAssemblyPhase contextPhase;
    private PipelineOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        jobRepository = new InMemoryJobRepository();
        summaryWriter = new JobSummaryWriter(tempDir.resolve("job-summary.json"));
        parsePhase = new ParsePhase();
        redactionPhase = new RedactionPhase();
        contextPhase = new ContextAssemblyPhase();
        orchestrator = new PipelineOrchestrator(parsePhase, redactionPhase, contextPhase, jobRepository, summaryWriter);
    }

    @Test
    @DisplayName("Pipeline executes Parse, Redaction, and ContextAssembly sequentially, recording RedactionReport to DB")
    void testSequentialPipelineExecution() throws IOException {
        UUID jobId = UUID.randomUUID();
        String idempotencyKey = "v2:test-pipeline-123";

        // Seed job in repository
        JobRecord initialJob = JobRecord.newClaimed(
                jobId, idempotencyKey, "billing", "Verify billing update",
                "jenkins", "feature/rec-billing", "bundle-hash-1", "claude-sonnet-5",
                "1.0.0", "2.0.0", "sha-head-999", "worker-1", 1L,
                Instant.now().plusSeconds(300)
        );
        jobRepository.insert(initialJob);

        // Prepare recording folder with recorded-elements.json
        Path recDir = tempDir.resolve("recording");
        Files.createDirectories(recDir);
        String recordedElementsJson = """
                {
                  "contractVersion": 2,
                  "recorderVersion": "2.0.0",
                  "capturedAt": "2026-10-14T03:00:00Z",
                  "targetArea": "billing",
                  "baseUrl": "https://app.example.com",
                  "snapshots": [
                    {
                      "stateId": "s1",
                      "url": "https://app.example.com/customers/123/billing?token=secret",
                      "prunedHtml": "<html><body><form><input value='secret-cc' name='cc'/><button data-testid='pay-btn'>Pay</button></form></body></html>"
                    }
                  ],
                  "interactions": [
                    {
                      "elementRefId": "e1",
                      "stateId": "s1",
                      "actionIndex": 0,
                      "tagName": "button",
                      "accessibleName": "Pay Now"
                    }
                  ]
                }
                """;
        Files.writeString(recDir.resolve("recorded-elements.json"), recordedElementsJson);

        TestGenerationJob job = new TestGenerationJob(
                jobId, idempotencyKey, "billing", "Verify billing update",
                "jenkins", "feature/rec-billing", "bundle-hash-1", "claude-sonnet-5",
                "1.0.0", "2.0.0", "sha-head-999", "worker-1", 1L,
                recDir
        );

        PipelineOutcome outcome = orchestrator.run(job);

        assertThat(outcome.exitCode()).isEqualTo(PipelineOutcome.EXIT_SUCCESS);
        assertThat(outcome.state()).isEqualTo("DONE");

        // Verify RedactionReport was saved to the DB
        Optional<JobRecord> updatedJob = jobRepository.findById(jobId);
        assertThat(updatedJob).isPresent();
        assertThat(updatedJob.get().redactionReport()).isNotNull();
        assertThat(updatedJob.get().redactionReport()).contains("redaction-v3");
        assertThat(updatedJob.get().redactionReport()).contains("attributesDropped");

        // Verify job-summary.json was written as build artifact
        Path summaryPath = summaryWriter.outputPath();
        assertThat(summaryPath).exists();
        JobSummary summary = Json.parse(Files.readString(summaryPath), JobSummary.class);
        assertThat(summary.jobId()).isEqualTo(jobId.toString());
        assertThat(summary.state()).isEqualTo("DONE");
        assertThat(summary.targetArea()).isEqualTo("billing");
    }

    @Test
    @DisplayName("Redaction failure halts pipeline fail-closed and marks job FAILED with code 60")
    void testRedactionFailClosedBehavior() throws IOException {
        UUID jobId = UUID.randomUUID();
        JobRecord initialJob = JobRecord.newClaimed(
                jobId, "v2:fail-closed-test", "auth", "Verify auth",
                "jenkins", "feature/rec-auth", "bundle-hash-1", "claude-sonnet-5",
                "1.0.0", "2.0.0", "sha-head-999", "worker-1", 1L,
                Instant.now().plusSeconds(300)
        );
        jobRepository.insert(initialJob);

        // Subclass DomRedactor to force a failure
        DomRedactor failingRedactor = new DomRedactor() {
            @Override
            public com.company.testgen.model.Redacted<PageSnapshot> redact(PageSnapshot raw) {
                throw new RedactionFailedException("Security constraint: corrupted DOM parse tree");
            }
        };

        RedactionPhase failingRedactionPhase = new RedactionPhase(failingRedactor);

        // Custom ParsePhase returning a plan with 1 snapshot
        ParsePhase customParse = new ParsePhase() {
            @Override
            public ParsedTestPlan execute(TestGenerationJob job) {
                return new ParsedTestPlan(
                        "auth",
                        "Verify auth",
                        List.of(new RecordedAction(0, "click", "e1", null, "s1")),
                        List.of(new PageSnapshot("s1", "https://app.example.com", "<div>bad</div>", List.of(), Instant.now())),
                        false,
                        false
                );
            }
        };

        PipelineOrchestrator failingOrchestrator = new PipelineOrchestrator(
                customParse, failingRedactionPhase, contextPhase, jobRepository, summaryWriter
        );

        TestGenerationJob job = TestGenerationJob.newJob(
                jobId, "v2:fail-closed-test", "auth", "Verify auth", "bundle-hash-1", "claude-sonnet-5", tempDir
        );

        PipelineOutcome outcome = failingOrchestrator.run(job);

        assertThat(outcome.exitCode()).isEqualTo(PipelineOutcome.EXIT_REDACTION_FAILURE);
        assertThat(outcome.state()).isEqualTo("FAILED");
        assertThat(outcome.failureReason()).contains("REDACTION_FAILED");

        // Job in DB must be marked FAILED
        JobRecord failedJob = jobRepository.findById(jobId).orElseThrow();
        assertThat(failedJob.state()).isEqualTo("FAILED");
        assertThat(failedJob.failureReason()).contains("REDACTION_FAILED");

        // job-summary.json must be emitted indicating failure
        Path summaryPath = summaryWriter.outputPath();
        assertThat(summaryPath).exists();
        JobSummary summary = Json.parse(Files.readString(summaryPath), JobSummary.class);
        assertThat(summary.state()).isEqualTo("FAILED");
        assertThat(summary.failureReason()).contains("REDACTION_FAILED");
    }
}
