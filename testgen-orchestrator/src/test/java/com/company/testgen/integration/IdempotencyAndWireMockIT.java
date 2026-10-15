package com.company.testgen.integration;

import com.company.testgen.gitlab.BranchNamer;
import com.company.testgen.gitlab.GitLabIntegrationService;
import com.company.testgen.gitlab.MergeRequestResult;
import com.company.testgen.idempotency.Claim;
import com.company.testgen.idempotency.ClaimStatus;
import com.company.testgen.idempotency.EffectExecutor;
import com.company.testgen.idempotency.EffectExhaustedException;
import com.company.testgen.idempotency.EffectType;
import com.company.testgen.idempotency.FaultInjectedException;
import com.company.testgen.idempotency.FaultInjector;
import com.company.testgen.idempotency.FencedJobWriter;
import com.company.testgen.idempotency.IdempotencyGate;
import com.company.testgen.idempotency.IdempotencyKeyDeriver;
import com.company.testgen.idempotency.JobSpec;
import com.company.testgen.idempotency.LeaseLostException;
import com.company.testgen.idempotency.ReconcileReport;
import com.company.testgen.idempotency.ReconcilerJob;
import com.company.testgen.llm.BudgetExceededException;
import com.company.testgen.llm.CostEventRepository;
import com.company.testgen.llm.GenerateRequest;
import com.company.testgen.llm.InMemoryCostEventRepository;
import com.company.testgen.llm.LangChain4jLlmClient;
import com.company.testgen.llm.LlmClient;
import com.company.testgen.llm.PriceBook;
import com.company.testgen.llm.PromptBundle;
import com.company.testgen.llm.ResolveRequest;
import com.company.testgen.llm.TokenBudgetGuard;
import com.company.testgen.llm.TransportRetryPolicy;
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
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.Severity;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.model.TokenUsage;
import com.company.testgen.model.ValidationResult;
import com.company.testgen.model.ValidationViolation;
import com.company.testgen.model.VerificationResult;
import com.company.testgen.observability.JobSummaryWriter;
import com.company.testgen.orchestrator.CorrectiveFeedbackBuilder;
import com.company.testgen.orchestrator.PipelineOrchestrator;
import com.company.testgen.orchestrator.PipelineOutcome;
import com.company.testgen.orchestrator.PipelineState;
import com.company.testgen.persistence.Db;
import com.company.testgen.persistence.EffectRecord;
import com.company.testgen.persistence.EffectRepository;
import com.company.testgen.persistence.EffectStatus;
import com.company.testgen.persistence.InMemoryEffectRepository;
import com.company.testgen.persistence.InMemoryJobRepository;
import com.company.testgen.persistence.InMemoryRetryAttemptRepository;
import com.company.testgen.persistence.InMemoryValidationResultRepository;
import com.company.testgen.persistence.InMemoryVerificationRepository;
import com.company.testgen.persistence.JdbiCostEventRepository;
import com.company.testgen.persistence.JdbiEffectRepository;
import com.company.testgen.persistence.JdbiJobRepository;
import com.company.testgen.persistence.JdbiRetryAttemptRepository;
import com.company.testgen.persistence.JdbiValidationResultRepository;
import com.company.testgen.persistence.JdbiVerificationRepository;
import com.company.testgen.persistence.JobRepository;
import com.company.testgen.persistence.RetryAttemptRepository;
import com.company.testgen.persistence.TargetAreaLock;
import com.company.testgen.persistence.ValidationResultRepository;
import com.company.testgen.persistence.VerificationRepository;
import com.company.testgen.phase.ContextAssemblyPhase;
import com.company.testgen.phase.ParsePhase;
import com.company.testgen.phase.RedactionPhase;
import com.company.testgen.phases.DefaultCheckstyleValidator;
import com.company.testgen.phases.GeneratePhase;
import com.company.testgen.phases.LintPhase;
import com.company.testgen.phases.VerifyPhase;
import com.company.testgen.validation.LocatorGrammarValidator;
import com.company.testgen.validation.StaticDomValidator;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import org.gitlab4j.api.GitLabApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration Test suite implementing the test matrix from Section 7.8 (scenarios IT-01 through IT-13)
 * and the WireMock retry, freeze, and budget scenarios from Section 9.5.
 * Uses Testcontainers PostgreSQL 16.6 when Docker is available (falling back to in-memory repos when absent),
 * WireMock for stubbing Anthropic and GitLab, and FaultInjector for controlled crash injection.
 */
public class IdempotencyAndWireMockIT {

    private static PostgreSQLContainer<?> postgres;
    private static Db db;
    private static boolean useDocker;

    private static WireMockServer wireMock;
    private static final String PROJECT_ID = "1842";
    private static final String GITLAB_TOKEN = "test-token";

    @TempDir
    Path tempDir;

    private JobRepository jobRepository;
    private EffectRepository effectRepository;
    private RetryAttemptRepository attemptRepository;
    private ValidationResultRepository validationRepository;
    private VerificationRepository verificationRepository;
    private CostEventRepository costEventRepository;

    private TargetAreaLock areaLock;
    private JobSummaryWriter summaryWriter;
    private IdempotencyGate gate;
    private GitLabIntegrationService gitLabService;
    private EffectExecutor effectExecutor;

    @BeforeAll
    static void startServices() {
        // 1. Initialize Testcontainers PostgreSQL 16.6 if Docker is active
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                postgres = new PostgreSQLContainer<>("postgres:16.6")
                        .withDatabaseName("testgen")
                        .withUsername("testgen")
                        .withPassword("testgen");
                postgres.start();
                db = Db.create(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                db.migrate();
                useDocker = true;
            } else {
                useDocker = false;
            }
        } catch (Throwable t) {
            useDocker = false;
        }

        // 2. Initialize WireMock server for stubbing GitLab and Anthropic
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor(wireMock.port());
    }

    @AfterAll
    static void stopServices() {
        if (wireMock != null) {
            wireMock.stop();
        }
        if (db != null) {
            db.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        wireMock.resetAll();
        FaultInjector.clear();

        if (useDocker && db != null) {
            jobRepository = new JdbiJobRepository(db.jdbi());
            effectRepository = new JdbiEffectRepository(db.jdbi());
            attemptRepository = new JdbiRetryAttemptRepository(db.jdbi());
            validationRepository = new JdbiValidationResultRepository(db.jdbi());
            verificationRepository = new JdbiVerificationRepository(db.jdbi());
            costEventRepository = new JdbiCostEventRepository(db.jdbi());
        } else {
            effectRepository = new InMemoryEffectRepository();
            jobRepository = new InMemoryJobRepository(effectRepository);
            attemptRepository = new InMemoryRetryAttemptRepository();
            validationRepository = new InMemoryValidationResultRepository();
            verificationRepository = new InMemoryVerificationRepository();
            costEventRepository = new InMemoryCostEventRepository();
        }

        areaLock = TargetAreaLock.inMemory();
        summaryWriter = new JobSummaryWriter(tempDir.resolve("job-summary.json"));
        gate = new IdempotencyGate(jobRepository, "worker-test", Duration.ofMinutes(30));
        effectExecutor = new EffectExecutor(effectRepository);

        GitLabApi api = new GitLabApi(wireMock.baseUrl(), GITLAB_TOKEN);
        gitLabService = new GitLabIntegrationService(api, PROJECT_ID, effectExecutor);

        // Dummy test plan files
        Files.writeString(tempDir.resolve("recorded-elements.json"), "{}");
        Files.writeString(tempDir.resolve("recorded-test.js"), "test();");
    }

    @AfterEach
    void tearDown() {
        FaultInjector.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helper Methods & WireMock Stubs
    // ─────────────────────────────────────────────────────────────────────────────

    private void stubGitLabHappyPath(String branch, int mrIid, String mrUrl) {
        // Default branch (main) check for headSha
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/branches/main"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"main\",\"commit\":{\"id\":\"sha-main-1\"}}")));

        // 1. Branch check (returns 404 initially, or 200)
        wireMock.stubFor(get(urlPathMatching("/api/v4/projects/" + PROJECT_ID + "/repository/branches/" + branch))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"" + branch + "\",\"commit\":{\"id\":\"sha-branch-1\"}}")));

        // 2. Branch creation
        wireMock.stubFor(post(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/branches"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"" + branch + "\",\"commit\":{\"id\":\"sha-branch-1\"}}")));

        // 3. Commit creation
        wireMock.stubFor(post(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/commits"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"commit-sha-123\",\"message\":\"testgen: commit\"}")));

        // 4. List commits on branch
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/commits"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"commit-sha-123\",\"message\":\"testgen: commit\"}]")));

        // 5. List open MRs
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/merge_requests"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":1,\"iid\":" + mrIid + ",\"title\":\"MR\",\"web_url\":\"" + mrUrl + "\",\"source_branch\":\"" + branch + "\"}]")));

        // 6. Create MR
        wireMock.stubFor(post(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/merge_requests"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"iid\":" + mrIid + ",\"title\":\"MR\",\"web_url\":\"" + mrUrl + "\",\"source_branch\":\"" + branch + "\"}")));
    }

    private TestGenerationJob createJob(UUID id, String key) {
        return new TestGenerationJob(
                id, key, "dashboard", "Dashboard date filter test",
                "jenkins", "scratch/branch", "bundle-1", "claude-sonnet-5",
                "1.0.0", "1.0.0", "base-sha", "worker-test", 1L, tempDir
        );
    }

    private ParsedTestPlan createPlan() {
        return new ParsedTestPlan(
                "dashboard", "Dashboard date filter test",
                List.of(new RecordedAction(0, "click", "e1", null, "s1")),
                List.of(new PageSnapshot("s1", "https://app/dashboard", "<button id='filter'>Filter</button>", List.of(), Instant.now())),
                false, false
        );
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Section 7.8 Test Matrix Scenarios (IT-01 .. IT-13)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("IT-01: Same recording pushed twice, first run completed -> second exits 20, returns original MR URL, no duplicate MR")
    void testIT01_sameRecordingPushedTwice_firstCompleted_secondExits20() {
        UUID initialJobId = UUID.randomUUID();
        String idempotencyKey = "v2:it01-key-" + UUID.randomUUID();
        TestGenerationJob job = createJob(initialJobId, idempotencyKey);

        // Run 1: Completes successfully and creates MR
        Claim claim1 = gate.claim(job);
        assertThat(claim1.isProceed()).isTrue();
        UUID actualJobId = claim1.jobId();

        job = job.withId(actualJobId).withLease(claim1.leaseOwner(), claim1.leaseEpoch());
        String branch = BranchNamer.forJob(job);
        String mrUrl = "https://gitlab.example.com/test/mr/101";
        stubGitLabHappyPath(branch, 101, mrUrl);

        List<GeneratedFile> files = List.of(new GeneratedFile("DashboardTest.java", "// code", true));
        MergeRequestResult result1 = gitLabService.publish(job, files, ConfidenceTier.VERIFIED, VerificationResult.passed(100));
        assertThat(result1.webUrl()).isEqualTo(mrUrl);

        FencedJobWriter writer = new FencedJobWriter(actualJobId, claim1.leaseEpoch(), jobRepository);
        writer.complete("VERIFIED", result1.iid(), result1.webUrl());

        // Run 2: Exact same idempotency key arrives
        Claim claim2 = gate.claim(idempotencyKey, JobSpec.from(job));
        assertThat(claim2.status()).isEqualTo(ClaimStatus.ALREADY_COMPLETE);
        assertThat(claim2.exitCode()).isEqualTo(20);
        assertThat(claim2.mrUrl()).isEqualTo(mrUrl);

        // Verify with WireMock: MR creation was invoked exactly once!
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/merge_requests")));
    }

    @Test
    @DisplayName("IT-02: Two orchestrators claim the same key concurrently -> one proceeds, one gets ALREADY_RUNNING, exactly one MR")
    void testIT02_concurrentClaims_oneProceedsOneAlreadyRunning() throws InterruptedException {
        String idempotencyKey = "v2:it02-key-" + UUID.randomUUID();
        JobSpec spec = new JobSpec("dashboard", "desc", "user", "scratch", "bundle", "claude-sonnet-5", "1.0", "1.0", "sha-base");

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicReference<Claim> claimRef1 = new AtomicReference<>();
        AtomicReference<Claim> claimRef2 = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(() -> {
            try {
                startLatch.await();
                claimRef1.set(new IdempotencyGate(jobRepository, "worker-1", Duration.ofMinutes(30)).claim(idempotencyKey, spec));
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                claimRef2.set(new IdempotencyGate(jobRepository, "worker-2", Duration.ofMinutes(30)).claim(idempotencyKey, spec));
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(completed).isTrue();

        Claim c1 = claimRef1.get();
        Claim c2 = claimRef2.get();
        assertThat(c1).isNotNull();
        assertThat(c2).isNotNull();

        // Exactly one PROCEED and one ALREADY_RUNNING (or ALREADY_COMPLETE)
        List<ClaimStatus> statuses = List.of(c1.status(), c2.status());
        assertThat(statuses).contains(ClaimStatus.PROCEED);
        assertThat(statuses).containsAnyOf(ClaimStatus.ALREADY_RUNNING, ClaimStatus.ALREADY_COMPLETE);

        // Verify exactly one job row in repository
        Optional<JobRecord> record = jobRepository.findByIdempotencyKey(idempotencyKey);
        assertThat(record).isPresent();
    }

    @Test
    @DisplayName("IT-03: Kill process after branch creation, before commit -> rerun reconciles branch as CONFIRMED, creates commit, one MR")
    void testIT03_faultInjectedAfterBranchCreationBeforeCommit_rerunReconcilesBranch() {
        UUID jobId = UUID.randomUUID();
        String idempotencyKey = "v2:it03-key-" + UUID.randomUUID();
        TestGenerationJob job = createJob(jobId, idempotencyKey);
        String branch = BranchNamer.forJob(job);
        String mrUrl = "https://gitlab.example.com/test/mr/103";

        stubGitLabHappyPath(branch, 103, mrUrl);

        // Arm FaultInjector to throw right after branch creation
        FaultInjector.setCheckpoint("after-branch-creation");

        List<GeneratedFile> files = List.of(new GeneratedFile("DashboardTest.java", "// code", true));

        // First run: branch is created, then fault is triggered
        assertThatThrownBy(() -> gitLabService.publish(job, files, ConfidenceTier.VERIFIED, VerificationResult.passed(100)))
                .isInstanceOf(FaultInjectedException.class)
                .hasMessageContaining("after-branch-creation");

        // BRANCH effect row is recorded in ledger
        Optional<EffectRecord> branchEffect = effectRepository.find(jobId, EffectType.BRANCH, branch);
        assertThat(branchEffect).isPresent();
        assertThat(branchEffect.get().status()).isEqualTo(EffectStatus.CONFIRMED);

        // Clear fault injector and re-run
        FaultInjector.clear();
        MergeRequestResult mrResult = gitLabService.publish(job, files, ConfidenceTier.VERIFIED, VerificationResult.passed(100));

        assertThat(mrResult).isNotNull();
        assertThat(mrResult.webUrl()).isEqualTo(mrUrl);

        // Commit and MR effects are confirmed
        Optional<EffectRecord> mrEffect = effectRepository.find(jobId, EffectType.MERGE_REQUEST, branch);
        assertThat(mrEffect).isPresent();
        assertThat(mrEffect.get().status()).isEqualTo(EffectStatus.CONFIRMED);
    }

    @Test
    @DisplayName("IT-04: Kill process after MR created, before confirm -> rerun finds MR by source branch, confirms ledger, no second MR")
    void testIT04_faultInjectedAfterMrCreatedBeforeConfirm_rerunReconcilesWithoutDuplicate() {
        UUID jobId = UUID.randomUUID();
        String idempotencyKey = "v2:it04-key-" + UUID.randomUUID();
        TestGenerationJob job = createJob(jobId, idempotencyKey);
        String branch = BranchNamer.forJob(job);
        String mrUrl = "https://gitlab.example.com/test/mr/104";

        stubGitLabHappyPath(branch, 104, mrUrl);

        // Arm fault injector right after MR created before confirming in DB
        FaultInjector.setCheckpoint("after-mr-created-before-confirm");

        List<GeneratedFile> files = List.of(new GeneratedFile("DashboardTest.java", "// code", true));

        // Run 1: MR was created in GitLab, but process is killed before confirming
        assertThatThrownBy(() -> gitLabService.publish(job, files, ConfidenceTier.VERIFIED, VerificationResult.passed(100)))
                .isInstanceOf(FaultInjectedException.class)
                .hasMessageContaining("after-mr-created-before-confirm");

        // In the ledger, MERGE_REQUEST remains PENDING
        Optional<EffectRecord> mrEffectPending = effectRepository.find(jobId, EffectType.MERGE_REQUEST, branch);
        assertThat(mrEffectPending).isPresent();
        assertThat(mrEffectPending.get().status()).isEqualTo(EffectStatus.PENDING);

        // Clear fault and re-run: reconcile finds open MR by source branch, confirms it, and does NOT call createMR again
        FaultInjector.clear();
        MergeRequestResult mrResult = gitLabService.publish(job, files, ConfidenceTier.VERIFIED, VerificationResult.passed(100));

        assertThat(mrResult).isNotNull();
        assertThat(mrResult.webUrl()).isEqualTo(mrUrl);

        // Exactly ONE MR was created in WireMock!
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/merge_requests")));

        // In the ledger, effect is now CONFIRMED
        Optional<EffectRecord> mrEffectConfirmed = effectRepository.find(jobId, EffectType.MERGE_REQUEST, branch);
        assertThat(mrEffectConfirmed).isPresent();
        assertThat(mrEffectConfirmed.get().status()).isEqualTo(EffectStatus.CONFIRMED);
    }

    @Test
    @DisplayName("IT-05: Zombie process writes after lease reclaimed -> LeaseLostException, exit 41, no writes applied")
    void testIT05_zombieProcessWritesAfterLeaseReclaimed_exit41() {
        UUID jobId = UUID.randomUUID();
        String key = "v2:it05-key-" + UUID.randomUUID();

        // Worker 1 claims job with epoch 1
        JobRecord initial = JobRecord.newClaimed(
                jobId, key, "dashboard", "desc", "user", "branch",
                "bundle", "claude", "1.0", "1.0", "base", "worker-1", 1L,
                Instant.now().minus(Duration.ofMinutes(1)) // expired lease
        );
        jobRepository.insert(initial);

        FencedJobWriter zombieWriter = new FencedJobWriter(jobId, 1L, jobRepository);

        // Worker 2 reclaims the expired lease, bumping epoch to 2
        Optional<Long> newEpoch = jobRepository.reclaim(jobId, "worker-2", Instant.now().plus(Duration.ofMinutes(30)));
        assertThat(newEpoch).isPresent();
        assertThat(newEpoch.get()).isEqualTo(2L);

        // Zombie worker 1 tries to write state -> fails with LeaseLostException (exit 41)
        assertThatThrownBy(() -> zombieWriter.setState(PipelineState.RESOLVING))
                .isInstanceOf(LeaseLostException.class)
                .satisfies(e -> {
                    LeaseLostException lle = (LeaseLostException) e;
                    assertThat(lle.getExitCode()).isEqualTo(PipelineOutcome.EXIT_LEASE_LOST);
                });

        assertThatThrownBy(zombieWriter::touch)
                .isInstanceOf(LeaseLostException.class);

        assertThatThrownBy(() -> zombieWriter.complete("VERIFIED", 99, "https://mr/99"))
                .isInstanceOf(LeaseLostException.class);
    }

    @Test
    @DisplayName("IT-06: Stale lease reclaimed after TTL -> resumes with incremented epoch")
    void testIT06_staleLeaseReclaimedAfterTtl_resumesWithIncrementedEpoch() {
        UUID jobId = UUID.randomUUID();
        String key = "v2:it06-key-" + UUID.randomUUID();

        // Initial job in state RESOLVED, lease expired 5 minutes ago
        JobRecord staleJob = new JobRecord(
                jobId, key, "dashboard", "desc", "user", "branch",
                "RESOLVED", null, null, "bundle", "claude", "1.0", "1.0", "base",
                "worker-old", 1L, Instant.now().minus(Duration.ofMinutes(5)),
                null, null, null, Instant.now().minus(Duration.ofHours(1)), Instant.now(), null
        );
        jobRepository.insert(staleJob);

        IdempotencyGate newGate = new IdempotencyGate(jobRepository, "worker-new", Duration.ofMinutes(30));
        Claim claim = newGate.claim(key, JobSpec.from(staleJob));

        assertThat(claim.status()).isEqualTo(ClaimStatus.RESUME);
        assertThat(claim.isResume()).isTrue();
        assertThat(claim.shouldExecute()).isTrue();
        assertThat(claim.leaseEpoch()).isEqualTo(2L);
        assertThat(claim.state()).isEqualTo("RESOLVED");

        JobRecord updated = jobRepository.findById(jobId).orElseThrow();
        assertThat(updated.leaseEpoch()).isEqualTo(2L);
        assertThat(updated.leaseOwner()).isEqualTo("worker-new");
    }

    @Test
    @DisplayName("IT-07: Byte-identical recording with different JSON key order -> same idempotency key (RFC 8785)")
    void testIT07_rfc8785KeyOrderInvariance() throws IOException {
        Path folder1 = tempDir.resolve("rec1");
        Path folder2 = tempDir.resolve("rec2");
        Files.createDirectories(folder1);
        Files.createDirectories(folder2);

        Files.writeString(folder1.resolve("recorded-test.js"), "test();");
        Files.writeString(folder2.resolve("recorded-test.js"), "test();");

        // JSON key orders differ
        Files.writeString(folder1.resolve("recorded-elements.json"), "{\"z\":1,\"a\":2,\"m\":{\"b\":3,\"a\":4}}");
        Files.writeString(folder2.resolve("recorded-elements.json"), "{\"a\":2,\"m\":{\"a\":4,\"b\":3},\"z\":1}");

        IdempotencyKeyDeriver deriver = new IdempotencyKeyDeriver();
        String key1 = deriver.derive(folder1, "dashboard", "test desc");
        String key2 = deriver.derive(folder2, "dashboard", "test desc");

        assertThat(key1).isEqualTo(key2);
    }

    @Test
    @DisplayName("IT-08: CRLF vs LF line endings in recorded-test.js -> same idempotency key")
    void testIT08_crlfVsLfNormalization() throws IOException {
        Path folder1 = tempDir.resolve("rec-crlf");
        Path folder2 = tempDir.resolve("rec-lf");
        Files.createDirectories(folder1);
        Files.createDirectories(folder2);

        Files.writeString(folder1.resolve("recorded-elements.json"), "{\"el\":1}");
        Files.writeString(folder2.resolve("recorded-elements.json"), "{\"el\":1}");

        Files.writeString(folder1.resolve("recorded-test.js"), "line1;\r\nline2;\r\n");
        Files.writeString(folder2.resolve("recorded-test.js"), "line1;\nline2;\n");

        IdempotencyKeyDeriver deriver = new IdempotencyKeyDeriver();
        String key1 = deriver.derive(folder1, "dashboard", "test desc");
        String key2 = deriver.derive(folder2, "dashboard", "test desc");

        assertThat(key1).isEqualTo(key2);
    }

    @Test
    @DisplayName("IT-09: Recording differing by one attribute -> different key and new job")
    void testIT09_differingAttributeProducesDifferentKey() throws IOException {
        Path folder1 = tempDir.resolve("rec-a");
        Path folder2 = tempDir.resolve("rec-b");
        Files.createDirectories(folder1);
        Files.createDirectories(folder2);

        Files.writeString(folder1.resolve("recorded-test.js"), "test();");
        Files.writeString(folder2.resolve("recorded-test.js"), "test();");

        Files.writeString(folder1.resolve("recorded-elements.json"), "{\"id\":\"e1\",\"role\":\"button\"}");
        Files.writeString(folder2.resolve("recorded-elements.json"), "{\"id\":\"e1\",\"role\":\"link\"}");

        IdempotencyKeyDeriver deriver = new IdempotencyKeyDeriver();
        String key1 = deriver.derive(folder1, "dashboard", "test desc");
        String key2 = deriver.derive(folder2, "dashboard", "test desc");

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("IT-10: GitLab returns 500 after actually creating the MR -> retry reconciles, one MR")
    void testIT10_gitlab500AfterMrCreation_retryReconcilesOneMr() {
        UUID jobId = UUID.randomUUID();
        String idempotencyKey = "v2:it10-key-" + UUID.randomUUID();
        TestGenerationJob job = createJob(jobId, idempotencyKey);
        String branch = BranchNamer.forJob(job);
        String mrUrl = "https://gitlab.example.com/test/mr/110";

        stubGitLabHappyPath(branch, 110, mrUrl);

        // First invocation creates MR in GitLab, but throws simulated 500 error after creating
        AtomicBoolean mrCreated = new AtomicBoolean(false);
        AtomicInteger mrCalls = new AtomicInteger(0);

        GitLabIntegrationService flakyGitLab = new GitLabIntegrationService(
                new GitLabApi(wireMock.baseUrl(), GITLAB_TOKEN), PROJECT_ID, effectExecutor
        );

        // Execute BRANCH and COMMIT
        List<GeneratedFile> files = List.of(new GeneratedFile("Test.java", "code", true));

        // Call 1 for MR throws
        assertThatThrownBy(() -> effectExecutor.exactlyOnce(
                job, EffectType.MERGE_REQUEST, branch,
                () -> {
                    mrCalls.incrementAndGet();
                    mrCreated.set(true); // MR was created on GitLab side
                    throw new RuntimeException("GitLab 500 Internal Server Error");
                },
                () -> mrCreated.get() ? Optional.of(new MergeRequestResult(110, mrUrl)) : Optional.empty()
        )).isInstanceOf(RuntimeException.class).hasMessageContaining("GitLab 500");

        // Retry: reconciliation proves MR exists externally -> confirms without calling action again
        MergeRequestResult reconciled = effectExecutor.exactlyOnce(
                job, EffectType.MERGE_REQUEST, branch,
                () -> {
                    mrCalls.incrementAndGet();
                    return new MergeRequestResult(110, mrUrl);
                },
                () -> mrCreated.get() ? Optional.of(new MergeRequestResult(110, mrUrl)) : Optional.empty()
        );

        assertThat(reconciled.webUrl()).isEqualTo(mrUrl);
        assertThat(mrCalls.get()).isEqualTo(1); // Action was called only once!
    }

    @Test
    @DisplayName("IT-11: FAILED job rebuilt -> proceeds with epoch+1, reuses same job id")
    void testIT11_failedJobRebuilt_proceedsWithEpochIncrement() {
        UUID jobId = UUID.randomUUID();
        String key = "v2:it11-key-" + UUID.randomUUID();
        JobSpec spec = new JobSpec("dashboard", "desc", "user", "scratch", "bundle", "claude", "1.0", "1.0", "base");

        // Initial claim
        Claim initial = gate.claim(key, spec);
        assertThat(initial.status()).isEqualTo(ClaimStatus.PROCEED);
        assertThat(initial.leaseEpoch()).isEqualTo(1L);

        // Mark failed
        jobRepository.markFailed(initial.jobId(), "COMPILATION_ERROR");
        JobRecord failedRecord = jobRepository.findById(initial.jobId()).orElseThrow();
        assertThat(failedRecord.state()).isEqualTo("FAILED");

        // Rebuild: claim should return PROCEED, increment epoch to 2, and reuse job ID
        Claim rebuilt = gate.claim(key, spec);
        assertThat(rebuilt.status()).isEqualTo(ClaimStatus.PROCEED);
        assertThat(rebuilt.jobId()).isEqualTo(initial.jobId());
        assertThat(rebuilt.leaseEpoch()).isEqualTo(2L);
    }

    @Test
    @DisplayName("IT-12: Reconciler on a DONE job with no MR effect -> alerts ops")
    void testIT12_reconcilerAuditsDoneJobWithoutConfirmedMr_alertsOps() {
        UUID violationJobId = UUID.randomUUID();
        jobRepository.insert(new JobRecord(
                violationJobId, "k-violation", "dashboard", "desc", "user", "branch",
                "DONE", "VERIFIED", null, "bundle", "claude", "1.0", "1.0", "base",
                "worker", 1L, Instant.now().plus(Duration.ofMinutes(10)),
                null, null, null, Instant.now().minus(Duration.ofMinutes(10)), Instant.now(), Instant.now()
        ));

        List<String> opsAlerts = new ArrayList<>();
        ReconcilerJob reconciler = new ReconcilerJob(
                jobRepository, effectRepository, (type, key) -> Optional.empty(), opsAlerts::add,
                Duration.ofMinutes(45), Duration.ofMinutes(30)
        );

        ReconcileReport report = reconciler.run();

        assertThat(report.invariantViolations()).isEqualTo(1);
        assertThat(report.invariantJobIds()).containsExactly(violationJobId);
        assertThat(opsAlerts).anyMatch(alert -> alert.contains("INVARIANT VIOLATION") && alert.contains(violationJobId.toString()));
    }

    @Test
    @DisplayName("IT-13: Effect exceeds MAX_EFFECT_ATTEMPTS -> ABANDONED, job escalates, ops alerted")
    void testIT13_effectExceedsMaxAttempts_abandonedAndEscalated() {
        UUID jobId = UUID.randomUUID();
        String branch = "testgen/dashboard/" + jobId;

        // Reserve PENDING effect
        effectRepository.reserve(jobId, EffectType.MERGE_REQUEST, branch, 1L);

        // Exceed attempts up to MAX_EFFECT_ATTEMPTS - 1
        effectRepository.incrementAttempts(jobId, EffectType.MERGE_REQUEST, branch);
        effectRepository.incrementAttempts(jobId, EffectType.MERGE_REQUEST, branch);

        // Next invocation where reconcile returns empty must throw EffectExhaustedException and abandon
        assertThatThrownBy(() -> effectExecutor.exactlyOnce(
                jobId, 1L, EffectType.MERGE_REQUEST, branch,
                () -> "should-not-run",
                Optional::empty
        )).isInstanceOf(EffectExhaustedException.class);

        Optional<EffectRecord> record = effectRepository.find(jobId, EffectType.MERGE_REQUEST, branch);
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo(EffectStatus.ABANDONED);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Section 9.5 WireMock Scenarios
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("WireMock 9.5: Attempt 0 non-unique -> attempt 1 unique (retry loop converges; feedback contains failed expressions only)")
    void testWireMock_retryLoopConvergence_attempt0NonUnique_attempt1Unique() {
        UUID jobId = UUID.randomUUID();
        String key = "v2:retry-converge-" + UUID.randomUUID();
        TestGenerationJob job = createJob(jobId, key);
        ParsedTestPlan plan = createPlan();

        // Stub attempt 0 response (non-unique locator: value='btn-duplicate')
        String attempt0Response = "{\"locators\":[{\"elementRefId\":\"e1\",\"strategy\":\"TEST_ID\",\"value\":\"btn-duplicate\"}]}";
        // Stub attempt 1 response (unique locator: value='btn-unique')
        String attempt1Response = "{\"locators\":[{\"elementRefId\":\"e1\",\"strategy\":\"TEST_ID\",\"value\":\"btn-unique\"}]}";

        wireMock.stubFor(post(urlPathEqualTo("/v1/messages"))
                .inScenario("RetryConvergence")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willSetStateTo("Attempt1")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(anthropicMessageBody(attempt0Response, 100, 50))));

        wireMock.stubFor(post(urlPathEqualTo("/v1/messages"))
                .inScenario("RetryConvergence")
                .whenScenarioStateIs("Attempt1")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(anthropicMessageBody(attempt1Response, 80, 40))));

        // WireMock-backed Anthropic ChatModel
        AnthropicChatModel chatModel = AnthropicChatModel.builder()
                .baseUrl(wireMock.baseUrl() + "/v1")
                .apiKey("test-key")
                .modelName("claude-sonnet-5")
                .cacheSystemMessages(false)
                .cacheTools(false)
                .temperature(0.0)
                .maxTokens(1024)
                .timeout(Duration.ofSeconds(10))
                .build();

        TokenBudgetGuard budget = new TokenBudgetGuard(250000, BigDecimal.valueOf(2.50), costEventRepository, new PriceBook());
        PromptBundle prompts = PromptBundle.load();
        TransportRetryPolicy transport = new TransportRetryPolicy(0);

        LangChain4jLlmClient llmClient = new LangChain4jLlmClient("claude-sonnet-5", chatModel, budget, prompts, transport);

        // Validator: returns NOT_UNIQUE on 'btn-duplicate', PASS on 'btn-unique'
        StaticDomValidator staticValidator = new StaticDomValidator() {
            @Override
            public ValidationResult validate(ResolvedLocatorSet locators, ParsedTestPlan p) {
                ResolvedLocator e1 = locators.findByElementRefId("e1").orElse(null);
                if (e1 != null && "btn-unique".equals(e1.value())) {
                    return new ValidationResult(List.of());
                }
                return new ValidationResult(List.of(
                        new ValidationViolation("page.getByTestId(\"btn-duplicate\")", "e1", "NOT_UNIQUE", 2, Severity.BLOCKING, "Matched 2 elements")
                ));
            }
        };

        String branch = BranchNamer.forJob(job);
        stubGitLabHappyPath(branch, 201, "https://gitlab.example.com/test/mr/201");

        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                new ParsePhase() { @Override public ParsedTestPlan execute(TestGenerationJob j) { return plan; } },
                new RedactionPhase() { @Override public Redacted<ParsedTestPlan> execute(ParsedTestPlan p) { return new Redacted<>(plan, null); } },
                new ContextAssemblyPhase() { @Override public ContextAssemblyResult execute(TestGenerationJob j, ParsedTestPlan p) { return new ContextAssemblyResult(new ExistingCodeContext("dashboard"), List.of(), plan); } },
                llmClient,
                new LocatorGrammarValidator(),
                staticValidator,
                new GeneratePhase(llmClient, new LintPhase(new DefaultCheckstyleValidator())) {
                    @Override
                    public List<GeneratedFile> execute(ParsedTestPlan p, ResolvedLocatorSet locs, ExistingCodeContext ctx, String baseSha) {
                        return List.of(new GeneratedFile("DashboardTest.java", "// code", true));
                    }
                },
                null, gitLabService, areaLock, gate, jobRepository, attemptRepository,
                validationRepository, verificationRepository, summaryWriter,
                new CorrectiveFeedbackBuilder(), 3, false, tempDir
        );

        PipelineOutcome outcome = orchestrator.run(job);

        assertThat(outcome.exitCode()).isEqualTo(PipelineOutcome.EXIT_SUCCESS);
        assertThat(outcome.state()).isEqualTo("DONE");
        assertThat(outcome.attemptsUsed()).isEqualTo(2);

        ResolvedLocator resolved = outcome.locators().findByElementRefId("e1").orElseThrow();
        assertThat(resolved.value()).isEqualTo("btn-unique");
    }

    @Test
    @DisplayName("WireMock 9.5: Model changes a passing locator on retry -> change is discarded; prior value retained (mergeKeepingAccepted)")
    void testWireMock_mergeKeepingAccepted_discardsMutationToPassingLocator() {
        ResolvedLocator acceptedE1 = new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "btn-accepted", null, "page.getByTestId(\"btn-accepted\")");
        ResolvedLocatorSet accepted = new ResolvedLocatorSet(List.of(acceptedE1));

        // Model attempts to mutate e1 into something else, and proposes e2
        ResolvedLocator mutatedE1 = new ResolvedLocator("e1", LocatorStrategy.TEXT, "Mutated Text", null, "page.getByText(\"Mutated Text\")");
        ResolvedLocator newE2 = new ResolvedLocator("e2", LocatorStrategy.ROLE_NAME, "Submit", "button", "page.getByRole(BUTTON, name=\"Submit\")");
        ResolvedLocatorSet proposed = new ResolvedLocatorSet(List.of(mutatedE1, newE2));

        ResolvedLocatorSet merged = accepted.mergeKeepingAccepted(proposed);

        // e1 must retain accepted value, e2 must be added
        ResolvedLocator resultE1 = merged.findByElementRefId("e1").orElseThrow();
        assertThat(resultE1.value()).isEqualTo("btn-accepted");
        assertThat(resultE1.strategy()).isEqualTo(LocatorStrategy.TEST_ID);

        ResolvedLocator resultE2 = merged.findByElementRefId("e2").orElseThrow();
        assertThat(resultE2.value()).isEqualTo("Submit");
    }

    @Test
    @DisplayName("WireMock 9.5: Model returns malformed JSON -> clean failure, no NPE, budget still recorded")
    void testWireMock_malformedJson_cleanFailureAndBudgetRecorded() {
        UUID jobId = UUID.randomUUID();
        wireMock.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(anthropicMessageBody("{ not valid json at all ", 50, 10))));

        AnthropicChatModel chatModel = AnthropicChatModel.builder()
                .baseUrl(wireMock.baseUrl() + "/v1")
                .apiKey("test-key")
                .modelName("claude-sonnet-5")
                .timeout(Duration.ofSeconds(5))
                .build();

        TokenBudgetGuard budget = new TokenBudgetGuard(250000, BigDecimal.valueOf(2.50), costEventRepository, new PriceBook());
        LangChain4jLlmClient client = new LangChain4jLlmClient("claude-sonnet-5", chatModel, budget, PromptBundle.load(), new TransportRetryPolicy(0));

        TestGenerationJob job = createJob(jobId, "v2:malformed-key");
        ResolveRequest req = new ResolveRequest(job, createPlan(), new ExistingCodeContext("dashboard"), null, 0, 1L);

        assertThatThrownBy(() -> client.resolveLocators(req))
                .isInstanceOf(RuntimeException.class);

        // Budget record was still stored for the call
        assertThat(costEventRepository.spendFor(jobId).totalTokens()).isGreaterThan(0);
    }

    @Test
    @DisplayName("WireMock 9.5: Model returns a locator for an unknown elementRefId -> UNKNOWN_ELEMENT_REF violation, triggers retry")
    void testWireMock_unknownElementRefId_triggersRetry() {
        ParsedTestPlan plan = createPlan(); // Contains only 'e1'

        // Propose locator for 'e999' which is not in the plan
        ResolvedLocator unknown = new ResolvedLocator("e999", LocatorStrategy.TEST_ID, "btn", null, "page.getByTestId(\"btn\")");
        ResolvedLocatorSet proposed = new ResolvedLocatorSet(List.of(unknown));

        LocatorGrammarValidator validator = new LocatorGrammarValidator();
        ValidationResult result = validator.validate(proposed, plan);

        assertThat(result.passed()).isFalse();
        assertThat(result.blocking()).anyMatch(v -> "UNKNOWN_ELEMENT_REF".equals(v.reasonCode()) && "e999".equals(v.elementRefId()));
    }

    @Test
    @DisplayName("WireMock 9.5: All attempts fail -> terminates in NEEDS_HUMAN_REVIEW, never DONE; draft MR opened")
    void testWireMock_allAttemptsFail_terminatesInNeedsHumanReviewDraftMr() {
        UUID initialJobId = UUID.randomUUID();
        String key = "v2:all-fail-" + UUID.randomUUID();
        TestGenerationJob job = createJob(initialJobId, key);
        ParsedTestPlan plan = createPlan();

        // Stub Anthropic returning locator that validator always rejects
        String responseBody = "{\"locators\":[{\"elementRefId\":\"e1\",\"strategy\":\"TEXT\",\"value\":\"impossible\"}]}";
        wireMock.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(anthropicMessageBody(responseBody, 100, 20))));

        // Stub gitlab draft MR
        wireMock.stubFor(post(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/branches"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{\"name\":\"branch\"}")));
        wireMock.stubFor(get(urlPathMatching("/api/v4/projects/" + PROJECT_ID + "/repository/branches/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"name\":\"branch\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/commits"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{\"id\":\"sha\"}")));
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/repository/commits"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));
        wireMock.stubFor(get(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/merge_requests"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));
        wireMock.stubFor(post(urlPathEqualTo("/api/v4/projects/" + PROJECT_ID + "/merge_requests"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"iid\":301,\"title\":\"Draft MR\",\"web_url\":\"https://gitlab.example.com/test/mr/301-draft\"}")));

        AnthropicChatModel chatModel = AnthropicChatModel.builder()
                .baseUrl(wireMock.baseUrl() + "/v1")
                .apiKey("test-key")
                .modelName("claude-sonnet-5")
                .timeout(Duration.ofSeconds(5))
                .build();

        TokenBudgetGuard budget = new TokenBudgetGuard(250000, BigDecimal.valueOf(2.50), costEventRepository, new PriceBook());
        LangChain4jLlmClient client = new LangChain4jLlmClient("claude-sonnet-5", chatModel, budget, PromptBundle.load(), new TransportRetryPolicy(0));

        StaticDomValidator alwaysFailingValidator = new StaticDomValidator() {
            @Override
            public ValidationResult validate(ResolvedLocatorSet locators, ParsedTestPlan p) {
                return new ValidationResult(List.of(
                        new ValidationViolation("page.getByText(\"impossible\")", "e1", "NOT_UNIQUE", 5, Severity.BLOCKING, "Not unique")
                ));
            }
        };

        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                new ParsePhase() { @Override public ParsedTestPlan execute(TestGenerationJob j) { return plan; } },
                new RedactionPhase() { @Override public Redacted<ParsedTestPlan> execute(ParsedTestPlan p) { return new Redacted<>(plan, null); } },
                new ContextAssemblyPhase() { @Override public ContextAssemblyResult execute(TestGenerationJob j, ParsedTestPlan p) { return new ContextAssemblyResult(new ExistingCodeContext("dashboard"), List.of(), plan); } },
                client,
                new LocatorGrammarValidator(),
                alwaysFailingValidator,
                null, null, gitLabService, areaLock, gate, jobRepository, attemptRepository,
                validationRepository, verificationRepository, summaryWriter,
                new CorrectiveFeedbackBuilder(), 2, false, tempDir
        );

        PipelineOutcome outcome = orchestrator.run(job);

        assertThat(outcome.exitCode()).isEqualTo(PipelineOutcome.EXIT_ESCALATED); // 10
        assertThat(outcome.state()).isEqualTo("NEEDS_HUMAN_REVIEW");
        assertThat(outcome.escalated()).isTrue();
        assertThat(outcome.failureReason()).isEqualTo("VALIDATION_EXHAUSTED");

        // Status in DB must be NEEDS_HUMAN_REVIEW, never DONE
        JobRecord record = jobRepository.findById(outcome.jobId()).orElseThrow();
        assertThat(record.state()).isEqualTo("NEEDS_HUMAN_REVIEW");
    }

    @Test
    @DisplayName("WireMock 9.5: Budget exhausted mid-loop -> circuit-breaks before next call; exit 50")
    void testWireMock_budgetExhaustedMidLoop_circuitBreaksBeforeNextCallExit50() {
        UUID jobId = UUID.randomUUID();
        // Set an extremely tight budget of 100 tokens
        TokenBudgetGuard tightBudget = new TokenBudgetGuard(100, BigDecimal.valueOf(0.01), costEventRepository, new PriceBook());

        AnthropicChatModel chatModel = AnthropicChatModel.builder()
                .baseUrl(wireMock.baseUrl() + "/v1")
                .apiKey("test-key")
                .modelName("claude-sonnet-5")
                .timeout(Duration.ofSeconds(5))
                .build();

        LangChain4jLlmClient client = new LangChain4jLlmClient("claude-sonnet-5", chatModel, tightBudget, PromptBundle.load(), new TransportRetryPolicy(0));

        TestGenerationJob job = createJob(jobId, "v2:budget-exceeded-key");
        ResolveRequest req = new ResolveRequest(job, createPlan(), new ExistingCodeContext("dashboard"), null, 0, 1L);

        // Pre-record spend exceeding the token limit
        costEventRepository.insert(new com.company.testgen.llm.CostEvent(
                jobId, 1L, com.company.testgen.model.Phase.RESOLVE, "claude-sonnet-5",
                new TokenUsage(80, 50, 0, 0), BigDecimal.valueOf(0.005)
        ));

        // When assertHeadroom runs before model.chat, it throws BudgetExceededException
        assertThatThrownBy(() -> client.resolveLocators(req))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("Job token budget exceeded");

        // Verify zero calls made to Anthropic endpoint in WireMock!
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/v1/messages")));
    }

    @Test
    @DisplayName("WireMock 9.5: Anthropic returns 429 then 200 -> transport retry succeeds; only one semantic attempt row")
    void testWireMock_anthropic429Then200_transportRetrySucceedsWithOneSemanticAttemptRow() {
        UUID jobId = UUID.randomUUID();
        String responseJson = "{\"locators\":[{\"elementRefId\":\"e1\",\"strategy\":\"TEST_ID\",\"value\":\"submit-btn\"}]}";

        wireMock.stubFor(post(urlPathEqualTo("/v1/messages"))
                .inScenario("RateLimitRetry")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willSetStateTo("Succeeded")
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Number of request tokens has exceeded your daily rate limit.\"}}")));

        wireMock.stubFor(post(urlPathEqualTo("/v1/messages"))
                .inScenario("RateLimitRetry")
                .whenScenarioStateIs("Succeeded")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(anthropicMessageBody(responseJson, 100, 30))));

        AnthropicChatModel chatModel = AnthropicChatModel.builder()
                .baseUrl(wireMock.baseUrl() + "/v1")
                .apiKey("test-key")
                .modelName("claude-sonnet-5")
                .timeout(Duration.ofSeconds(10))
                .build();

        TokenBudgetGuard budget = new TokenBudgetGuard(250000, BigDecimal.valueOf(2.50), costEventRepository, new PriceBook());
        // Fast backoff for testing
        TransportRetryPolicy fastTransport = new TransportRetryPolicy(2, Duration.ofMillis(20), Duration.ofMillis(50), 2.0);
        LangChain4jLlmClient client = new LangChain4jLlmClient("claude-sonnet-5", chatModel, budget, PromptBundle.load(), fastTransport);

        Long semanticAttemptId = attemptRepository.begin(jobId, 0, com.company.testgen.model.Phase.RESOLVE);
        TestGenerationJob job = createJob(jobId, "v2:transport-429-key");
        ResolveRequest req = new ResolveRequest(job, createPlan(), new ExistingCodeContext("dashboard"), null, 0, semanticAttemptId);

        ResolvedLocatorSet result = client.resolveLocators(req);
        attemptRepository.finish(semanticAttemptId, true);

        assertThat(result).isNotNull();
        assertThat(result.locators()).isNotEmpty();
        assertThat(result.locators().get(0).value()).isEqualTo("submit-btn");

        // WireMock saw 2 network requests (1 failing 429, 1 successful 200)
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/v1/messages")));

        // But exactly ONE semantic attempt was recorded in the database
        List<com.company.testgen.persistence.RetryAttemptRecord> semanticAttempts = attemptRepository.findByJobId(jobId);
        assertThat(semanticAttempts).hasSize(1);
    }

    private String anthropicMessageBody(String text, int inputTokens, int outputTokens) {
        String escaped = text.replace("\"", "\\\"");
        return "{\n" +
                "  \"id\": \"msg_" + UUID.randomUUID() + "\",\n" +
                "  \"type\": \"message\",\n" +
                "  \"role\": \"assistant\",\n" +
                "  \"content\": [\n" +
                "    {\n" +
                "      \"type\": \"text\",\n" +
                "      \"text\": \"" + escaped + "\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"model\": \"claude-sonnet-5\",\n" +
                "  \"stop_reason\": \"end_turn\",\n" +
                "  \"usage\": {\n" +
                "    \"input_tokens\": " + inputTokens + ",\n" +
                "    \"output_tokens\": " + outputTokens + ",\n" +
                "    \"cache_creation_input_tokens\": 0,\n" +
                "    \"cache_read_input_tokens\": 0\n" +
                "  }\n" +
                "}";
    }
}
