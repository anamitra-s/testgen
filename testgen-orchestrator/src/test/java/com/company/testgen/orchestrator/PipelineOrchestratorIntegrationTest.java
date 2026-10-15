package com.company.testgen.orchestrator;

import com.company.testgen.gitlab.GitLabIntegrationService;
import com.company.testgen.gitlab.MergeRequestResult;
import com.company.testgen.idempotency.EffectExecutor;
import com.company.testgen.idempotency.IdempotencyGate;
import com.company.testgen.llm.GenerateRequest;
import com.company.testgen.llm.LlmClient;
import com.company.testgen.llm.ResolveRequest;
import com.company.testgen.model.ConfidenceTier;
import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.GeneratedFile;
import com.company.testgen.model.GeneratedFileSet;
import com.company.testgen.model.JobRecord;
import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.PageSnapshot;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.RecordedAction;
import com.company.testgen.model.Redacted;
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
import com.company.testgen.persistence.TargetAreaLock;
import com.company.testgen.phase.ContextAssemblyPhase;
import com.company.testgen.phase.ParsePhase;
import com.company.testgen.phase.RedactionPhase;
import com.company.testgen.phases.AuthSessionProvider;
import com.company.testgen.phases.DefaultCheckstyleValidator;
import com.company.testgen.phases.GeneratePhase;
import com.company.testgen.phases.LintPhase;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineOrchestratorIntegrationTest {

    @TempDir
    Path tempDir;

    private InMemoryJobRepository jobRepository;
    private InMemoryRetryAttemptRepository attemptRepository;
    private InMemoryValidationResultRepository validationRepository;
    private InMemoryVerificationRepository verificationRepository;
    private JobSummaryWriter summaryWriter;
    private TargetAreaLock areaLock;
    private IdempotencyGate gate;

    @BeforeEach
    void setUp() throws IOException {
        jobRepository = new InMemoryJobRepository();
        attemptRepository = new InMemoryRetryAttemptRepository();
        validationRepository = new InMemoryValidationResultRepository();
        verificationRepository = new InMemoryVerificationRepository();
        summaryWriter = new JobSummaryWriter(tempDir.resolve("job-summary.json"));
        areaLock = TargetAreaLock.inMemory();
        gate = new IdempotencyGate(jobRepository);

        // Prepare dummy recording files so ParsePhase has valid inputs if touched
        Files.writeString(tempDir.resolve("recorded-elements.json"), "{}");
        Files.writeString(tempDir.resolve("recorded-test.js"), "test();");
    }

    private TestGenerationJob createJob(UUID jobId, String idempotencyKey) {
        return new TestGenerationJob(
                jobId, idempotencyKey, "billing", "Verify billing update",
                "jenkins", "scratch", "bundle-hash", "claude-sonnet-5",
                "1.0.0", "2.0.0", "sha-initial", "worker-1", 1L, tempDir
        );
    }

    private ParsedTestPlan createPlan() {
        return new ParsedTestPlan(
                "billing", "Verify billing update",
                List.of(new RecordedAction(0, "click", "e1", null, "s1"),
                        new RecordedAction(1, "click", "e2", null, "s1")),
                List.of(new PageSnapshot("s1", "https://app.example.com", "<button>Pay</button>", List.of(), Instant.now())),
                false, false
        );
    }

    @Test
    @DisplayName("Already completed job returns exit code 20 (no-op) via IdempotencyGate")
    void testAlreadyCompleteNoOp() {
        UUID jobId = UUID.randomUUID();
        String idempotencyKey = "v2:complete-key";

        JobRecord doneRecord = new JobRecord(
                jobId, idempotencyKey, "billing", "desc", "jenkins", "scratch",
                "DONE", "VERIFIED", null, "bundle-hash", "claude-sonnet-5",
                "1.0.0", "2.0.0", "sha-1", "worker-old", 1L, Instant.now(),
                null, 42, "https://gitlab/mr/42", Instant.now(), Instant.now(), Instant.now()
        );
        jobRepository.insert(doneRecord);

        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                new ParsePhase(), new RedactionPhase(), new ContextAssemblyPhase(),
                null, new LocatorGrammarValidator(), new StaticDomValidator(),
                null, null, null, areaLock, gate, jobRepository, attemptRepository,
                validationRepository, verificationRepository, summaryWriter,
                new CorrectiveFeedbackBuilder(), 3, false, tempDir
        );

        PipelineOutcome outcome = orchestrator.run(createJob(jobId, idempotencyKey));
        assertThat(outcome.exitCode()).isEqualTo(PipelineOutcome.EXIT_NOOP); // 20
        assertThat(outcome.state()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("Retry loop freezes passing locators in code via mergeKeepingAccepted")
    void testRetryLoopLocatorFreeze() {
        UUID jobId = UUID.randomUUID();
        String idempotencyKey = "v2:retry-freeze-key";
        TestGenerationJob job = createJob(jobId, idempotencyKey);
        ParsedTestPlan plan = createPlan();

        ParsePhase parsePhase = new ParsePhase() {
            @Override
            public ParsedTestPlan execute(TestGenerationJob j) {
                return plan;
            }
        };

        RedactionPhase redactionPhase = new RedactionPhase() {
            @Override
            public Redacted<ParsedTestPlan> execute(ParsedTestPlan p) {
                return new Redacted<>(plan, null);
            }
        };

        ContextAssemblyPhase contextPhase = new ContextAssemblyPhase() {
            @Override
            public ContextAssemblyResult execute(TestGenerationJob j, ParsedTestPlan p) {
                return new ContextAssemblyResult(new ExistingCodeContext("billing"), List.of(), plan);
            }
        };

        // Attempt 0: Proposes e1 and e2. e1 passes, e2 fails NOT_UNIQUE.
        ResolvedLocator l1 = new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "btn-e1", null, "page.getByTestId(\"btn-e1\")");
        ResolvedLocator l2_bad = new ResolvedLocator("e2", LocatorStrategy.ROLE_NAME, "btn-e2", "button", "page.getByRole(\"button\")");
        ResolvedLocatorSet attempt0Proposed = new ResolvedLocatorSet(List.of(l1, l2_bad));

        // Attempt 1: Model wrongly tries to mutate e1 into e1_mutated, and proposes e2_fixed.
        ResolvedLocator l1_mutated = new ResolvedLocator("e1", LocatorStrategy.TEXT, "Mutated Text", null, "page.getByText(\"Mutated Text\")");
        ResolvedLocator l2_fixed = new ResolvedLocator("e2", LocatorStrategy.TEST_ID, "btn-e2-fixed", null, "page.getByTestId(\"btn-e2-fixed\")");
        ResolvedLocatorSet attempt1Proposed = new ResolvedLocatorSet(List.of(l1_mutated, l2_fixed));

        AtomicInteger callCount = new AtomicInteger(0);
        LlmClient fakeLlm = new LlmClient() {
            @Override
            public ResolvedLocatorSet resolveLocators(ResolveRequest request) {
                int count = callCount.getAndIncrement();
                return count == 0 ? attempt0Proposed : attempt1Proposed;
            }

            @Override
            public GeneratedFileSet generateCode(GenerateRequest request) {
                return new GeneratedFileSet(List.of(new GeneratedFile("BillingTest.java", "code", true)));
            }
        };

        LocatorGrammarValidator grammarValidator = new LocatorGrammarValidator() {
            @Override
            public ValidationResult validate(ResolvedLocatorSet locators, ParsedTestPlan p) {
                return new ValidationResult(List.of());
            }
        };

        StaticDomValidator staticValidator = new StaticDomValidator() {
            @Override
            public ValidationResult validate(ResolvedLocatorSet set, ParsedTestPlan p) {
                ResolvedLocator e2 = set.findByElementRefId("e2").orElse(null);
                if (e2 != null && "btn-e2-fixed".equals(e2.value())) {
                    return new ValidationResult(List.of()); // passed on attempt 1
                }
                return new ValidationResult(List.of(
                        new ValidationViolation("page.getByRole(\"button\")", "e2", "NOT_UNIQUE", 2, Severity.BLOCKING, "matched 2 elements")
                ));
            }
        };

        FakeGitLabService gitLab = new FakeGitLabService();
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
                new CorrectiveFeedbackBuilder(), 3, false, tempDir
        );

        PipelineOutcome outcome = orchestrator.run(job);

        assertThat(outcome.exitCode()).isEqualTo(PipelineOutcome.EXIT_SUCCESS);
        assertThat(outcome.state()).isEqualTo("DONE");

        // Verify that attempt 1 merged keeping accepted e1 from attempt 0 (l1, not l1_mutated!)
        ResolvedLocator finalE1 = outcome.locators().findByElementRefId("e1").orElseThrow();
        assertThat(finalE1.value()).isEqualTo("btn-e1");
        assertThat(finalE1.strategy()).isEqualTo(LocatorStrategy.TEST_ID);

        ResolvedLocator finalE2 = outcome.locators().findByElementRefId("e2").orElseThrow();
        assertThat(finalE2.value()).isEqualTo("btn-e2-fixed");
    }

    @Test
    @DisplayName("Validation exhausted escalates to NEEDS_HUMAN_REVIEW draft MR with exit code 10")
    void testValidationExhaustedEscalates() {
        UUID jobId = UUID.randomUUID();
        String idempotencyKey = "v2:exhaust-key";
        TestGenerationJob job = createJob(jobId, idempotencyKey);
        ParsedTestPlan plan = createPlan();

        ParsePhase parsePhase = new ParsePhase() {
            @Override
            public ParsedTestPlan execute(TestGenerationJob j) {
                return plan;
            }
        };

        RedactionPhase redactionPhase = new RedactionPhase() {
            @Override
            public Redacted<ParsedTestPlan> execute(ParsedTestPlan p) {
                return new Redacted<>(plan, null);
            }
        };

        ContextAssemblyPhase contextPhase = new ContextAssemblyPhase() {
            @Override
            public ContextAssemblyResult execute(TestGenerationJob j, ParsedTestPlan p) {
                return new ContextAssemblyResult(new ExistingCodeContext("billing"), List.of(), plan);
            }
        };

        ResolvedLocator l1 = new ResolvedLocator("e1", LocatorStrategy.TEXT, "bad", null, "page.getByText(\"bad\")");
        LlmClient fakeLlm = new LlmClient() {
            @Override
            public ResolvedLocatorSet resolveLocators(ResolveRequest request) {
                return new ResolvedLocatorSet(List.of(l1));
            }

            @Override
            public GeneratedFileSet generateCode(GenerateRequest request) {
                return new GeneratedFileSet(List.of());
            }
        };

        LocatorGrammarValidator grammarValidator = new LocatorGrammarValidator() {
            @Override
            public ValidationResult validate(ResolvedLocatorSet locators, ParsedTestPlan p) {
                return new ValidationResult(List.of());
            }
        };

        StaticDomValidator staticValidator = new StaticDomValidator() {
            @Override
            public ValidationResult validate(ResolvedLocatorSet set, ParsedTestPlan p) {
                return new ValidationResult(List.of(
                        new ValidationViolation("page.getByText(\"bad\")", "e1", "NOT_UNIQUE", 5, Severity.BLOCKING, "matched 5")
                ));
            }
        };

        FakeGitLabService gitLab = new FakeGitLabService();

        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                parsePhase, redactionPhase, contextPhase, fakeLlm,
                grammarValidator, staticValidator, null, null,
                gitLab, areaLock, gate, jobRepository, attemptRepository,
                validationRepository, verificationRepository, summaryWriter,
                new CorrectiveFeedbackBuilder(), 2, false, tempDir
        );

        PipelineOutcome outcome = orchestrator.run(job);

        assertThat(outcome.exitCode()).isEqualTo(PipelineOutcome.EXIT_ESCALATED); // 10
        assertThat(outcome.state()).isEqualTo("NEEDS_HUMAN_REVIEW");
        assertThat(outcome.escalated()).isTrue();
        assertThat(outcome.failureReason()).isEqualTo("VALIDATION_EXHAUSTED");

        assertThat(gitLab.escalationPublishedCount).isEqualTo(1);
        assertThat(gitLab.lastEscalationReason).isEqualTo("VALIDATION_EXHAUSTED");
        assertThat(gitLab.lastEscalationAttempt).isEqualTo(2);
    }

    @Test
    @DisplayName("main HEAD SHA change triggers context re-analysis before code generation (FR-27)")
    void testMainHeadShaRecheckTriggersReanalysis() {
        UUID jobId = UUID.randomUUID();
        TestGenerationJob job = createJob(jobId, "v2:sha-change-key");
        ParsedTestPlan plan = createPlan();

        ParsePhase parsePhase = new ParsePhase() {
            @Override
            public ParsedTestPlan execute(TestGenerationJob j) {
                return plan;
            }
        };

        RedactionPhase redactionPhase = new RedactionPhase() {
            @Override
            public Redacted<ParsedTestPlan> execute(ParsedTestPlan p) {
                return new Redacted<>(plan, null);
            }
        };

        AtomicInteger contextAnalysisCount = new AtomicInteger(0);
        ContextAssemblyPhase contextPhase = new ContextAssemblyPhase() {
            @Override
            public ContextAssemblyResult execute(TestGenerationJob j, ParsedTestPlan p) {
                contextAnalysisCount.incrementAndGet();
                return new ContextAssemblyResult(new ExistingCodeContext("billing"), List.of(), plan);
            }
        };

        ResolvedLocator loc = new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "btn1", null, "page.getByTestId(\"btn1\")");
        LlmClient fakeLlm = new LlmClient() {
            @Override
            public ResolvedLocatorSet resolveLocators(ResolveRequest request) {
                return new ResolvedLocatorSet(List.of(loc));
            }

            @Override
            public GeneratedFileSet generateCode(GenerateRequest request) {
                return new GeneratedFileSet(List.of(new GeneratedFile("test.java", "code", true)));
            }
        };

        LocatorGrammarValidator grammarValidator = new LocatorGrammarValidator() {
            @Override
            public ValidationResult validate(ResolvedLocatorSet locators, ParsedTestPlan p) {
                return new ValidationResult(List.of());
            }
        };

        StaticDomValidator staticValidator = new StaticDomValidator() {
            @Override
            public ValidationResult validate(ResolvedLocatorSet set, ParsedTestPlan p) {
                return new ValidationResult(List.of());
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
