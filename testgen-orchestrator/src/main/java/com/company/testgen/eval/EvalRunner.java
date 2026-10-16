package com.company.testgen.eval;

import com.company.testgen.llm.LlmClient;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.observability.JobSummaryWriter;
import com.company.testgen.orchestrator.CorrectiveFeedbackBuilder;
import com.company.testgen.orchestrator.PipelineOrchestrator;
import com.company.testgen.orchestrator.PipelineOutcome;
import com.company.testgen.persistence.EvalCaseResultRecord;
import com.company.testgen.persistence.EvalRepository;
import com.company.testgen.persistence.EvalRunRecord;
import com.company.testgen.persistence.InMemoryEvalRepository;
import com.company.testgen.persistence.InMemoryJobRepository;
import com.company.testgen.persistence.InMemoryRetryAttemptRepository;
import com.company.testgen.persistence.InMemoryValidationResultRepository;
import com.company.testgen.persistence.InMemoryVerificationRepository;
import com.company.testgen.persistence.TargetAreaLock;
import com.company.testgen.phases.ParsePhase;
import com.company.testgen.phases.RedactionPhase;
import com.company.testgen.validation.LocatorGrammarValidator;
import com.company.testgen.validation.StaticDomValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Executes evaluation suites with bounded concurrency (§8.5).
 * Exercises Parse -> Redact -> Resolve -> L0 -> L1 with frozen code context.
 * Generate, Lint, Verify, and all GitLab effects are DISABLED.
 * Supports statistical gating and --promote-baseline=<runId>.
 */
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private final EvalRepository evalRepo;
    private final Corpus corpus;
    private final LlmClient llm;
    private final EvalScorer scorer;
    private final EvalGate gate;

    public EvalRunner(EvalRepository evalRepo, Corpus corpus, LlmClient llm, EvalScorer scorer, EvalGate gate) {
        this.evalRepo = evalRepo != null ? evalRepo : new InMemoryEvalRepository();
        this.corpus = Objects.requireNonNull(corpus, "corpus cannot be null");
        this.llm = llm;
        this.scorer = scorer != null ? scorer : new EvalScorer();
        this.gate = gate != null ? gate : new EvalGate();
    }

    public EvalRunner(EvalRepository evalRepo, Corpus corpus, LlmClient llm) {
        this(evalRepo, corpus, llm, new EvalScorer(), new EvalGate());
    }

    /**
     * Executes the evaluation suite according to the provided run configuration (§8.5).
     */
    public EvalRun run(SuiteSpec suite, RunConfig config) {
        Objects.requireNonNull(suite, "suite cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        UUID runId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        int repeats = config.repeats() > 0 ? config.repeats() : suite.repeats();

        List<EvalCase> cases = corpus.load(suite);
        log.info("Starting eval run {} for suite '{}' (cases={}, repeats={}, concurrency={})",
                runId, suite.name(), cases.size(), repeats, config.concurrency());

        EvalRunRecord initialRecord = new EvalRunRecord(
                runId, suite.name(), config.trigger(), config.modelId(), config.promptBundleHash(),
                config.orchestratorVersion(), config.gitRef(), repeats, cases.size(), false,
                "{}", null, null, startedAt, null
        );
        evalRepo.insertRun(initialRecord);

        List<CaseScore> scores = Collections.synchronizedList(new ArrayList<>());

        // Bounded parallelism: respects rate limits without serializing a long suite (§8.5)
        try (ExecutorService pool = Executors.newFixedThreadPool(config.concurrency())) {
            List<Future<CaseScore>> futures = new ArrayList<>();
            for (EvalCase c : cases) {
                for (int r = 0; r < repeats; r++) {
                    final int repeat = r;
                    futures.add(pool.submit(() -> runOne(runId, c, repeat, config)));
                }
            }

            for (Future<CaseScore> f : futures) {
                try {
                    scores.add(f.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Eval execution interrupted", e);
                } catch (ExecutionException e) {
                    log.error("Eval case execution failed: {}", e.getCause().getMessage(), e.getCause());
                }
            }
        }

        // Persist all per-case results
        List<EvalCaseResultRecord> caseRecords = new ArrayList<>();
        int repeatIndex = 0;
        for (CaseScore s : scores) {
            caseRecords.add(s.toRecord(runId, repeatIndex++));
        }
        evalRepo.insertCaseResults(caseRecords);

        // Aggregate deterministic metrics (§8.3)
        EvalMetrics metrics = EvalMetrics.aggregate(caseRecords);
        Instant finishedAt = Instant.now();

        EvalRun candidateRun = new EvalRun(
                runId, suite.name(), config.trigger(), config.modelId(), config.promptBundleHash(),
                config.orchestratorVersion(), config.gitRef(), repeats, cases.size(), false,
                metrics, caseRecords, startedAt, finishedAt
        );

        // Retrieve existing baseline for (suite, model_id, prompt_bundle_hash) (§8.5)
        Optional<EvalRun> baseline = evalRepo.findBaseline(suite.name(), config.modelId(), config.promptBundleHash())
                .map(rec -> EvalRun.fromRecord(rec, evalRepo.findCaseResultsByRunId(rec.id())));

        // Retrieve oldest baseline in last 90 days for ratchet-drift guard (§8.5)
        List<EvalRunRecord> longHorizonBaselines = evalRepo.findBaselinesOlderThan(
                suite.name(), config.modelId(), Duration.ofDays(90));
        Optional<EvalRun> longHorizonBaseline = longHorizonBaselines.isEmpty()
                ? Optional.empty()
                : Optional.of(EvalRun.fromRecord(longHorizonBaselines.get(0), evalRepo.findCaseResultsByRunId(longHorizonBaselines.get(0).id())));

        // Statistical evaluation gate (§8.4)
        GateOutcome gateOutcome = gate.evaluate(candidateRun, baseline, longHorizonBaseline);

        // Automatic staleness quarantine on nightly runs (§8.7)
        if ("NIGHTLY".equalsIgnoreCase(config.trigger())) {
            List<EvalRunRecord> allRuns = evalRepo.findAllRuns();
            List<EvalRun> nightlyRuns = allRuns.stream()
                    .filter(r -> "NIGHTLY".equalsIgnoreCase(r.trigger()) && suite.name().equalsIgnoreCase(r.suite()))
                    .map(r -> EvalRun.fromRecord(r, evalRepo.findCaseResultsByRunId(r.id())))
                    .toList();
            gate.checkAndApplyStalenessQuarantine(nightlyRuns, corpus);
        }

        // Update run record in repository
        EvalRunRecord completedRecord = candidateRun.toRecord(
                gateOutcome.status(),
                gateOutcome.failures().isEmpty() ? "{}" : "{\"failures\":[\"" + String.join("\",\"", gateOutcome.failures()) + "\"]}"
        );
        evalRepo.updateRun(completedRecord);

        log.info("Eval run {} completed with gate status {}: pass_rate={:.1f}%, exact_match={:.1f}%, escalation={:.1f}%",
                runId, gateOutcome.status(),
                metrics.firstAttemptPassRate() * 100,
                metrics.locatorExactMatchRate() * 100,
                metrics.escalationRate() * 100);

        return candidateRun;
    }

    /**
     * Promotes a completed evaluation run as the new baseline (§8.5).
     */
    public void promoteBaseline(UUID runId) {
        Objects.requireNonNull(runId, "runId cannot be null");
        evalRepo.promoteBaseline(runId);
        log.info("Promoted eval run {} to baseline", runId);
    }

    /**
     * Runs Parse -> Redact -> Resolve -> L0 -> L1 with the retry loop, using the case's
     * FROZEN existing-code context. Generate, Lint, Verify, and all GitLab effects are
     * DISABLED — the harness measures locator resolution, and must never touch a repo (§8.5).
     */
    public CaseScore runOne(UUID runId, EvalCase c, int repeat, RunConfig config) {
        Path tempSummary;
        try {
            tempSummary = Files.createTempFile("eval-summary-", ".json");
            tempSummary.toFile().deleteOnExit();
        } catch (IOException e) {
            tempSummary = Path.of("eval-summary-" + UUID.randomUUID() + ".json");
        }

        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                new ParsePhase(),
                new RedactionPhase(),
                new FrozenContextAssemblyPhase(c.existingCodeContext()),
                llm,
                new LocatorGrammarValidator(),
                new StaticDomValidator(),
                null,                   // Generate disabled (§8.5)
                null,                   // Verify disabled (§8.5)
                null,                   // GitLab disabled (§8.5)
                TargetAreaLock.inMemory(),
                null,
                new InMemoryJobRepository(),
                new InMemoryRetryAttemptRepository(),
                new InMemoryValidationResultRepository(),
                new InMemoryVerificationRepository(),
                new JobSummaryWriter(tempSummary),
                new CorrectiveFeedbackBuilder(),
                3,
                false,
                null
        );

        UUID jobId = UUID.randomUUID();
        TestGenerationJob job = new TestGenerationJob(
                jobId,
                "eval-" + c.caseId() + "-r" + repeat + "-" + UUID.randomUUID(),
                c.targetArea(),
                c.description(),
                "eval-runner",
                "eval",
                config.promptBundleHash(),
                config.modelId(),
                config.orchestratorVersion(),
                "2.1.0",
                "main",
                "eval-worker",
                0L,
                c.caseDir()
        );

        PipelineOutcome outcome = orchestrator.run(job);
        return scorer.score(c, outcome);
    }

    public EvalRepository repository() {
        return evalRepo;
    }

    public Corpus corpus() {
        return corpus;
    }

    public EvalGate gate() {
        return gate;
    }
}
