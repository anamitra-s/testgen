package com.company.testgen.orchestrator;

import com.company.testgen.gitlab.GitLabIntegrationService;
import com.company.testgen.gitlab.MergeRequestResult;
import com.company.testgen.idempotency.Claim;
import com.company.testgen.idempotency.EffectExecutor;
import com.company.testgen.idempotency.IdempotencyGate;
import com.company.testgen.idempotency.JobSpec;
import com.company.testgen.llm.GenerateRequest;
import com.company.testgen.llm.LlmClient;
import com.company.testgen.llm.ResolveRequest;
import com.company.testgen.model.ConfidenceTier;
import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.GeneratedFile;
import com.company.testgen.model.GeneratedFileSet;
import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.RedactionReport;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.Severity;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.model.TokenUsage;
import com.company.testgen.model.ValidationResult;
import com.company.testgen.model.ValidationViolation;
import com.company.testgen.model.VerificationResult;
import com.company.testgen.observability.JobSummaryWriter;
import com.company.testgen.persistence.InMemoryEffectRepository;
import com.company.testgen.persistence.InMemoryJobRepository;
import com.company.testgen.persistence.InMemoryRetryAttemptRepository;
import com.company.testgen.persistence.InMemoryValidationResultRepository;
import com.company.testgen.persistence.InMemoryVerificationRepository;
import com.company.testgen.persistence.JobRepository;
import com.company.testgen.persistence.RetryAttemptRepository;
import com.company.testgen.persistence.TargetAreaLock;
import com.company.testgen.persistence.ValidationResultRepository;
import com.company.testgen.persistence.VerificationRepository;
import com.company.testgen.phases.AuthSessionProvider;
import com.company.testgen.phases.ContextAssemblyPhase;
import com.company.testgen.phases.DefaultCheckstyleValidator;
import com.company.testgen.phases.GeneratePhase;
import com.company.testgen.phases.LintPhase;
import com.company.testgen.phases.ParsePhase;
import com.company.testgen.phases.RedactionPhase;
import com.company.testgen.phases.VerifyPhase;
import com.company.testgen.validation.LocatorGrammarValidator;
import com.company.testgen.validation.StaticDomValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineOrchestratorIntegrationTest {

    @TempDir
    Path tempDir;

    private JobRepository jobRepository;
    private RetryAttemptRepository attemptRepository;
    private ValidationResultRepository validationRepository;
    private VerificationRepository verificationRepository;
    private TargetAreaLock areaLock;
    private IdempotencyGate gate;
    private JobSummaryWriter summaryWriter;
    private ParsePhase parsePhase;
    private RedactionPhase redactionPhase;
    private ContextAssemblyPhase contextPhase;

    @BeforeEach
    void setUp() {
        jobRepository = new InMemoryJobRepository();
        attemptRepository = new InMemoryRetryAttemptRepository();
        validationRepository = new InMemoryValidationResultRepository();
        verificationRepository = new InMemoryVerificationRepository();
        areaLock = TargetAreaLock.inMemory();
        gate = new IdempotencyGate(jobRepository);
        summaryWriter = new JobSummaryWriter(tempDir.resolve("job-summary.json"));
        parsePhase = new ParsePhase();
        redactionPhase = new RedactionPhase();
        contextPhase = new ContextAssemblyPhase();
    }

    @Test
    @DisplayName("Idempotency gate returns no-op when job with same key is already claimed/running")
    void testIdempotencyGateNoOpWhenAlreadyRunning() throws IOException {
        UUID jobId1 = UUID.randomUUID();
        String idempotencyKey = "v2:concurrent-key-1";

        // Job 1 claims the key
        TestGenerationJob job1 = new TestGenerationJob(
                jobId1, idempotencyKey, "dashboard", "Dashboard check",
                "jenkins", "main", "bundle-1", "claude-sonnet-5",
                "1.0", "1.0", "sha-1", "worker-1", 1L, tempDir
        );
        Claim claim1 = gate.claim(idempotencyKey, JobSpec.from(job1));
        assertThat(claim1.shouldExecute()).isTrue();

        // Job 2 arrives concurrently with the same idempotency key
        UUID jobId2 = UUID.randomUUID();
        TestGenerationJob job2 = new TestGenerationJob(
                jobId2, idempotencyKey, "dashboard", "Dashboard check",
                "jenkins", "main", "bundle-1", "claude-sonnet-5",
                "1.0", "1.0", "sha-1", "worker-2", 1L, tempDir
        );

        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                parsePhase, redactionPhase, contextPhase, null,
                null, null, null, null,
                null, areaLock, gate, jobRepository, attemptRepository,
                validationRepository, verificationRepository, summaryWriter,
                new CorrectiveFeedbackBuilder(), 3, false, tempDir
        );

        PipelineOutcome outcome = orchestrator.run(job2);

        // Orchestrator must detect already running job and return no-op with code 20 (§9.2)
        assertThat(outcome.exitCode()).isEqualTo(PipelineOutcome.EXIT_NOOP);
        assertThat(outcome.state()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("Banned locator triggers scoped retry feedback and merges keeping accepted locators across attempts")
    void testMergeKeepingAcceptedAcrossRetryAttempts() throws IOException {
        UUID jobId = UUID.randomUUID();
        String idempotencyKey = "v2:retry-freeze-test";

        Path recDir = tempDir.resolve("recording-retry");
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
                      "url": "https://app.example.com/billing",
                      "prunedHtml": "<html><body><button data-testid='submit-btn'>Submit</button><button id='cancel-btn'>Cancel</button></body></html>"
                    }
                  ],
                  "interactions": [
                    {
                      "elementRefId": "e1",
                      "stateId": "s1",
                      "actionIndex": 0,
                      "tagName": "button",
                      "accessibleName": "Submit"
                    },
                    {
                      "elementRefId": "e2",
                      "stateId": "s1",
                      "actionIndex": 1,
                      "tagName": "button",
                      "accessibleName": "Cancel"
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

        // First attempt returns:
        // e1 -> TEST_ID (Valid)
        // e2 -> ROLE_NAME with invalid role (Fails validation with INVALID_ROLE)
        // Second attempt returns:
        // e2 -> TEXT "Cancel" (Valid, matches exactly 1 element)
        AtomicInteger attemptCounter = new AtomicInteger(0);
        LlmClient fakeLlm = new LlmClient() {
            @Override
            public ResolvedLocatorSet resolveLocators(ResolveRequest request) {
                int attempt = attemptCounter.incrementAndGet();
                if (attempt == 1) {
                    return new ResolvedLocatorSet(List.of(
                            new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "submit-btn"),
                            new ResolvedLocator("e2", LocatorStrategy.ROLE_NAME, "Cancel", "not_a_valid_aria_role", null)
                    ));
                } else {
                    // Attempt 2: feedback must contain the failure reason for e2
                    assertThat(request.correctionFeedback()).isNotNull();
                    assertThat(request.correctionFeedback()).contains("INVALID_ROLE");
                    return new ResolvedLocatorSet(List.of(
                            new ResolvedLocator("e2", LocatorStrategy.TEXT, "Cancel")
                    ));
                }
            }

            @Override
            public GeneratedFileSet generateCode(GenerateRequest request) {
                return new GeneratedFileSet(List.of(
                        new GeneratedFile("src/test/java/BillingTest.java", "class BillingTest {}", true)
                ));
            }
        };

        LocatorGrammarValidator grammarValidator = new LocatorGrammarValidator();
        StaticDomValidator staticValidator = new StaticDomValidator();

        GeneratePhase generatePhase = new GeneratePhase(fakeLlm, new LintPhase(new DefaultCheckstyleValidator())) {
            @Override
            public List<GeneratedFile> execute(ParsedTestPlan p, ResolvedLocatorSet locs, ExistingCodeContext ctx, String baseSha) {
                // Must have merged locators: e1 from attempt 1, e2 from attempt 2
                assertThat(locs.findByElementRefId("e1")).isPresent();
                assertThat(locs.findByElementRefId("e1").get().strategy()).isEqualTo(LocatorStrategy.TEST_ID);
                assertThat(locs.findByElementRefId("e2")).isPresent();
                assertThat(locs.findByElementRefId("e2").get().strategy()).isEqualTo(LocatorStrategy.TEXT);
                return List.of(new GeneratedFile("src/test/java/BillingTest.java", "class BillingTest {}", true));
            }
        };

        VerifyPhase verifyPhase = new VerifyPhase(new AuthSessionProvider(), Duration.ofMinutes(1)) {
            @Override
            public VerificationResult execute(TestGenerationJob j, List<GeneratedFile> files, Path ws) {
                return VerificationResult.passed(1200);
            }
        };

        FakeGitLabService gitLab = new FakeGitLabService();

        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                parsePhase, redactionPhase, contextPhase, fakeLlm,
                grammarValidator, staticValidator, generatePhase, verifyPhase,
                gitLab, areaLock, gate, jobRepository, attemptRepository,
                validationRepository, verificationRepository, summaryWriter,
                new CorrectiveFeedbackBuilder(), 3, false, tempDir
        );

        PipelineOutcome outcome = orchestrator.run(job);

        assertThat(outcome.exitCode()).isEqualTo(PipelineOutcome.EXIT_SUCCESS);
        assertThat(outcome.state()).isEqualTo("DONE");
        assertThat(outcome.attemptsUsed()).isEqualTo(2);

        // Check that final locators contain both e1 (from attempt 1) and e2 (from attempt 2)
        assertThat(outcome.locators().findByElementRefId("e1")).isPresent();
        assertThat(outcome.locators().findByElementRefId("e2")).isPresent();
    }

    @Test
    @DisplayName("Escalation creates Draft MR and sets outcome to NEEDS_HUMAN_REVIEW with exit code 10 (§9.2)")
    void testEscalationWhenAttemptsExhausted() throws IOException {
        UUID jobId = UUID.randomUUID();
        String idempotencyKey = "v2:escalate-test";

        Path recDir = tempDir.resolve("recording-escalate");
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
                      "url": "https://app.example.com/billing",
                      "prunedHtml": "<html><body><div>Duplicate</div><div>Duplicate</div></body></html>"
                    }
                  ],
                  "interactions": [
                    {
                      "elementRefId": "e1",
                      "stateId": "s1",
                      "actionIndex": 0,
                      "tagName": "div",
                      "accessibleName": "Duplicate"
                    }
                  ]
                }
                """;
        Files.writeString(recDir.resolve("recorded-elements.json"), recordedElementsJson);

        TestGenerationJob job = TestGenerationJob.newJob(
                jobId, idempotencyKey, "billing", "Verify billing update", "bundle-1", "claude-sonnet-5", recDir
        );

        // LLM returns locator that fails static validation across all retries
        LlmClient alwaysFailingLlm = new LlmClient() {
            @Override
            public ResolvedLocatorSet resolveLocators(ResolveRequest request) {
                return new ResolvedLocatorSet(List.of(
                        new ResolvedLocator("e1", LocatorStrategy.TEXT, "Bad Text", null, "page.getByText(\"Bad Text\")")
                ));
            }

            @Override
            public GeneratedFileSet generateCode(GenerateRequest request) {
                return new GeneratedFileSet(List.of());
            }
        };

        LocatorGrammarValidator grammarValidator = new LocatorGrammarValidator();
        StaticDomValidator staticValidator = new StaticDomValidator() {
            @Override
            public ValidationResult validate(ResolvedLocatorSet set, ParsedTestPlan plan) {
                return new ValidationResult(List.of(
                        new ValidationViolation("page.getByText(\"Bad Text\")", "e1", "NOT_UNIQUE", 0, Severity.BLOCKING, "Not unique")
                ));
            }
        };

        FakeGitLabService gitLab = new FakeGitLabService();

        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                parsePhase, redactionPhase, contextPhase, alwaysFailingLlm,
                grammarValidator, staticValidator, null, null,
                gitLab, areaLock, gate, jobRepository, attemptRepository,
                validationRepository, verificationRepository, summaryWriter,
                new CorrectiveFeedbackBuilder(), 2, false, tempDir
        );

        PipelineOutcome outcome = orchestrator.run(job);

        // Escalation contract (§9.2): exit code 10, state NEEDS_HUMAN_REVIEW
        assertThat(outcome.exitCode()).isEqualTo(PipelineOutcome.EXIT_ESCALATED);
        assertThat(outcome.state()).isEqualTo("NEEDS_HUMAN_REVIEW");
        assertThat(outcome.escalated()).isTrue();
        assertThat(outcome.failureReason()).isEqualTo("VALIDATION_EXHAUSTED");

        // Verify GitLab escalation was published
        assertThat(gitLab.escalationPublishedCount).isEqualTo(1);
        assertThat(gitLab.lastEscalationReason).isEqualTo("VALIDATION_EXHAUSTED");
    }

    @Test
    @DisplayName("HEAD SHA change after initial context re-runs ContextAssembly before generate (FR-27)")
    void testHeadShaChangeReRunsContextAssembly() throws IOException {
        UUID jobId = UUID.randomUUID();
        String idempotencyKey = "v2:sha-change-test";

        Path recDir = tempDir.resolve("recording-sha");
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
                      "url": "https://app.example.com/billing",
                      "prunedHtml": "<html><body><button data-testid='submit-btn'>Submit</button></body></html>"
                    }
                  ],
                  "interactions": [
                    {
                      "elementRefId": "e1",
                      "stateId": "s1",
                      "actionIndex": 0,
                      "tagName": "button",
                      "accessibleName": "Submit"
                    }
                  ]
                }
                """;
        Files.writeString(recDir.resolve("recorded-elements.json"), recordedElementsJson);

        TestGenerationJob job = new TestGenerationJob(
                jobId, idempotencyKey, "billing", "Verify billing update",
                "jenkins", "feature/rec-billing", "bundle-hash-1", "claude-sonnet-5",
                "1.0.0", "2.0.0", "sha-initial", "worker-1", 1L,
                recDir
        );

        LlmClient fakeLlm = new LlmClient() {
            @Override
            public ResolvedLocatorSet resolveLocators(ResolveRequest request) {
                return new ResolvedLocatorSet(List.of(
                        new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "submit-btn")
                ));
            }

            @Override
            public GeneratedFileSet generateCode(GenerateRequest request) {
                return new GeneratedFileSet(List.of(new GeneratedFile("BillingTest.java", "code", true)));
            }
        };

        LocatorGrammarValidator grammarValidator = new LocatorGrammarValidator();
        StaticDomValidator staticValidator = new StaticDomValidator();

        AtomicInteger contextAnalysisCount = new AtomicInteger(0);
        ContextAssemblyPhase contextPhase = new ContextAssemblyPhase() {
            @Override
            public ContextAssemblyResult execute(TestGenerationJob j, ParsedTestPlan plan) {
                contextAnalysisCount.incrementAndGet();
                return new ContextAssemblyResult(new ExistingCodeContext("billing", List.of()), List.of(), plan);
            }
        };

        // Simulate main branch moving forward concurrently while resolve was running
        AtomicInteger shaQueries = new AtomicInteger(0);
        FakeGitLabService gitLab = new FakeGitLabService() {
            @Override
            public String headSha(String branch) {
                int q = shaQueries.getAndIncrement();
                return q == 0 ? "sha-initial" : "sha-advanced-by-concurrent-mr";
            }
        };

        GeneratePhase generatePhase = new GeneratePhase(fakeLlm, new LintPhase(new DefaultCheckstyleValidator())) {
            @Override
            public List<GeneratedFile> execute(ParsedTestPlan p, ResolvedLocatorSet locs, ExistingCodeContext ctx, String baseSha) {
                return List.of(new GeneratedFile("src/test/java/BillingTest.java", "code", true));
            }
        };

        VerifyPhase verifyPhase = new VerifyPhase(new AuthSessionProvider(), Duration.ofMinutes(1)) {
            @Override
            public VerificationResult execute(TestGenerationJob j, List<GeneratedFile> files, Path ws) {
                return VerificationResult.skipped("mutatesData");
            }
        };

        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                parsePhase, redactionPhase, contextPhase, fakeLlm,
                grammarValidator, staticValidator, generatePhase, verifyPhase,
                gitLab, areaLock, gate, jobRepository, attemptRepository,
                validationRepository, verificationRepository, summaryWriter,
                new CorrectiveFeedbackBuilder(), 1, false, tempDir
        );

        PipelineOutcome outcome = orchestrator.run(job);

        assertThat(outcome.exitCode()).isEqualTo(PipelineOutcome.EXIT_SUCCESS);
        // contextPhase should be called twice: once initially, and once on HEAD SHA change
        assertThat(contextAnalysisCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("TargetAreaLock is released before VerifyPhase executes and re-acquired before publish (SPR-01)")
    void testTargetAreaLockNotHeldAcrossVerifyPhase() throws IOException {
        UUID jobId = UUID.randomUUID();
        String idempotencyKey = "v2:lock-scope-test";

        Path recDir = tempDir.resolve("recording-lock");
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
                      "url": "https://app.example.com/billing",
                      "prunedHtml": "<html><body><button data-testid='pay-btn'>Pay</button></body></html>"
                    }
                  ],
                  "interactions": [
                    {
                      "elementRefId": "e1",
                      "stateId": "s1",
                      "actionIndex": 0,
                      "tagName": "button",
                      "accessibleName": "Pay"
                    }
                  ]
                }
                """;
        Files.writeString(recDir.resolve("recorded-elements.json"), recordedElementsJson);

        TestGenerationJob job = new TestGenerationJob(
                jobId, idempotencyKey, "billing", "Verify billing update",
                "jenkins", "feature/rec-billing", "bundle-hash-1", "claude-sonnet-5",
                "1.0.0", "2.0.0", "sha-initial", "worker-1", 1L,
                recDir
        );

        LlmClient fakeLlm = new LlmClient() {
            @Override
            public ResolvedLocatorSet resolveLocators(ResolveRequest request) {
                return new ResolvedLocatorSet(List.of(
                        new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "pay-btn")
                ));
            }

            @Override
            public GeneratedFileSet generateCode(GenerateRequest request) {
                return new GeneratedFileSet(List.of(new GeneratedFile("BillingTest.java", "code", true)));
            }
        };

        LocatorGrammarValidator grammarValidator = new LocatorGrammarValidator();
        StaticDomValidator staticValidator = new StaticDomValidator();

        GeneratePhase generatePhase = new GeneratePhase(fakeLlm, new LintPhase(new DefaultCheckstyleValidator())) {
            @Override
            public List<GeneratedFile> execute(ParsedTestPlan p, ResolvedLocatorSet locs, ExistingCodeContext ctx, String baseSha) {
                return List.of(new GeneratedFile("src/test/java/BillingTest.java", "code", true));
            }
        };

        AtomicBoolean lockWasAvailableDuringVerify = new AtomicBoolean(false);
        VerifyPhase verifyPhase = new VerifyPhase(new AuthSessionProvider(), Duration.ofMinutes(1)) {
            @Override
            public VerificationResult execute(TestGenerationJob j, List<GeneratedFile> files, Path ws) {
                // If TargetAreaLock is NOT held across VerifyPhase, tryAcquire MUST succeed!
                Optional<TargetAreaLock.Lock> acquired = areaLock.tryAcquire(j.targetArea());
                if (acquired.isPresent()) {
                    lockWasAvailableDuringVerify.set(true);
                    acquired.get().close();
                }
                return VerificationResult.passed(500);
            }
        };

        AtomicBoolean lockWasHeldDuringPublish = new AtomicBoolean(false);
        FakeGitLabService gitLab = new FakeGitLabService() {
            @Override
            public MergeRequestResult publish(
                    TestGenerationJob j, List<GeneratedFile> files, ConfidenceTier tier,
                    VerificationResult verify, ResolvedLocatorSet locators, ExistingCodeContext context,
                    RedactionReport redaction, TokenUsage tokenUsage, Duration duration,
                    int attempts, BigDecimal estimatedCostUsd
            ) {
                // During publish, TargetAreaLock MUST be re-acquired (tryAcquire fails)
                Optional<TargetAreaLock.Lock> acquired = areaLock.tryAcquire(j.targetArea());
                if (acquired.isEmpty()) {
                    lockWasHeldDuringPublish.set(true);
                } else {
                    acquired.get().close();
                }
                return new MergeRequestResult(99, "https://gitlab/mr/99");
            }
        };

        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                parsePhase, redactionPhase, contextPhase, fakeLlm,
                grammarValidator, staticValidator, generatePhase, verifyPhase,
                gitLab, areaLock, gate, jobRepository, attemptRepository,
                validationRepository, verificationRepository, summaryWriter,
                new CorrectiveFeedbackBuilder(), 1, false, tempDir
        );

        PipelineOutcome outcome = orchestrator.run(job);

        assertThat(outcome.exitCode()).isEqualTo(PipelineOutcome.EXIT_SUCCESS);
        assertThat(outcome.state()).isEqualTo("DONE");
        assertThat(lockWasAvailableDuringVerify.get()).isTrue();
        assertThat(lockWasHeldDuringPublish.get()).isTrue();
    }

    private static class FakeGitLabService extends GitLabIntegrationService {
        int escalationPublishedCount = 0;
        String lastEscalationReason = null;
        int lastEscalationAttempt = 0;

        FakeGitLabService() {
            super("https://gitlab.example.com", "dummy", "proj-1", new EffectExecutor(new InMemoryEffectRepository()));
        }

        @Override
        public String headSha(String branch) {
            return "sha-initial";
        }

        @Override
        public MergeRequestResult publish(
                TestGenerationJob job,
                List<GeneratedFile> files,
                ConfidenceTier tier,
                VerificationResult verify,
                ResolvedLocatorSet locators,
                ExistingCodeContext context,
                RedactionReport redaction,
                TokenUsage tokenUsage,
                Duration duration,
                int attempts,
                BigDecimal estimatedCostUsd
        ) {
            return new MergeRequestResult(77, "https://gitlab/mr/77");
        }

        @Override
        public MergeRequestResult publishEscalation(
                TestGenerationJob job,
                List<GeneratedFile> files,
                ConfidenceTier tier,
                String reasonCode,
                ResolvedLocatorSet accepted,
                ValidationResult validation,
                int attemptsUsed
        ) {
            escalationPublishedCount++;
            lastEscalationReason = reasonCode;
            lastEscalationAttempt = attemptsUsed;
            return new MergeRequestResult(88, "https://gitlab/mr/88-draft");
        }
    }
}
