package com.company.testgen.orchestrator;

import com.company.testgen.gitlab.BranchNamer;
import com.company.testgen.gitlab.GitLabIntegrationService;
import com.company.testgen.gitlab.MergeRequestResult;
import com.company.testgen.idempotency.Claim;
import com.company.testgen.idempotency.ClaimStatus;
import com.company.testgen.idempotency.FencedJobWriter;
import com.company.testgen.idempotency.IdempotencyGate;
import com.company.testgen.idempotency.JobSpec;
import com.company.testgen.idempotency.LeaseHeartbeat;
import com.company.testgen.idempotency.LeaseLostException;
import com.company.testgen.llm.BudgetExceededException;
import com.company.testgen.llm.LlmClient;
import com.company.testgen.llm.ResolveRequest;
import com.company.testgen.model.ConfidenceTier;
import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.GeneratedFile;
import com.company.testgen.model.JobRecord;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.Phase;
import com.company.testgen.model.Redacted;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.model.TokenUsage;
import com.company.testgen.model.ValidationResult;
import com.company.testgen.model.VerificationResult;
import com.company.testgen.observability.JobSummary;
import com.company.testgen.observability.JobSummaryWriter;
import com.company.testgen.observability.Mdc;
import com.company.testgen.persistence.InMemoryRetryAttemptRepository;
import com.company.testgen.persistence.InMemoryValidationResultRepository;
import com.company.testgen.persistence.InMemoryVerificationRepository;
import com.company.testgen.persistence.JobRepository;
import com.company.testgen.persistence.RetryAttemptRepository;
import com.company.testgen.persistence.TargetAreaLock;
import com.company.testgen.persistence.ValidationResultRepository;
import com.company.testgen.persistence.VerificationRepository;
import com.company.testgen.phase.ContextAssemblyPhase;
import com.company.testgen.phase.ParsePhase;
import com.company.testgen.phase.RedactionPhase;
import com.company.testgen.phases.EscalationRequired;
import com.company.testgen.phases.GeneratePhase;
import com.company.testgen.phases.VerifyPhase;
import com.company.testgen.redaction.RedactionFailedException;
import com.company.testgen.validation.LocatorGrammarValidator;
import com.company.testgen.validation.StaticDomValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Core orchestrator executing test generation pipeline phases sequentially (§6.13, §9.3).
 * Integrates:
 * - Idempotency gate & claim protocol (§7.3)
 * - Fenced job writer & lease heartbeat (§7.4)
 * - Fail-closed DOM redaction (§6.7, §9.4)
 * - Bounded Resolve + L0/L1 retry loop with code-enforced locator freeze (§6.6, §6.13)
 * - TargetAreaLock & main HEAD SHA re-check (§6.13, FR-26, FR-27)
 * - Generate phase & Verify phase (L2) (§6.8, §6.12)
 * - GitLabIntegrationService (§6.15)
 * - Escalation handling (NEEDS_HUMAN_REVIEW) (§6.15, §9.2)
 */
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private final ParsePhase parsePhase;
    private final RedactionPhase redactionPhase;
    private final ContextAssemblyPhase contextPhase;
    private final LlmClient llm;
    private final LocatorGrammarValidator grammarValidator;
    private final StaticDomValidator staticValidator;
    private final GeneratePhase generatePhase;
    private final VerifyPhase verifyPhase;
    private final GitLabIntegrationService gitLab;
    private final TargetAreaLock areaLock;
    private final IdempotencyGate gate;
    private final JobRepository jobs;
    private final RetryAttemptRepository attempts;
    private final ValidationResultRepository validations;
    private final VerificationRepository verifications;
    private final JobSummaryWriter summaryWriter;
    private final CorrectiveFeedbackBuilder feedbackBuilder;
    private final int maxAttempts;
    private final boolean verifyBlocking;
    private final Path workspace;


    public PipelineOrchestrator(
            ParsePhase parsePhase,
            RedactionPhase redactionPhase,
            ContextAssemblyPhase contextPhase,
            JobRepository jobs,
            JobSummaryWriter summaryWriter
    ) {
        this(parsePhase, redactionPhase, contextPhase, null, null, null, null, null, null, null, null, jobs, null, null, null, summaryWriter, null, 3, false, null);
    }


    public PipelineOrchestrator(
            ParsePhase parsePhase,
            RedactionPhase redactionPhase,
            ContextAssemblyPhase contextPhase,
            LlmClient llm,
            LocatorGrammarValidator grammarValidator,
            StaticDomValidator staticValidator,
            GeneratePhase generatePhase,
            VerifyPhase verifyPhase,
            GitLabIntegrationService gitLab,
            TargetAreaLock areaLock,
            IdempotencyGate gate,
            JobRepository jobs,
            RetryAttemptRepository attempts,
            ValidationResultRepository validations,
            VerificationRepository verifications,
            JobSummaryWriter summaryWriter,
            CorrectiveFeedbackBuilder feedbackBuilder,
            int maxAttempts,
            boolean verifyBlocking,
            Path workspace
    ) {
        this.parsePhase = Objects.requireNonNull(parsePhase, "parsePhase cannot be null");
        this.redactionPhase = Objects.requireNonNull(redactionPhase, "redactionPhase cannot be null");
        this.contextPhase = Objects.requireNonNull(contextPhase, "contextPhase cannot be null");
        this.llm = llm;
        this.grammarValidator = grammarValidator != null ? grammarValidator : new LocatorGrammarValidator();
        this.staticValidator = staticValidator != null ? staticValidator : new StaticDomValidator();
        this.generatePhase = generatePhase;
        this.verifyPhase = verifyPhase;
        this.gitLab = gitLab;
        this.areaLock = areaLock != null ? areaLock : TargetAreaLock.inMemory();
        this.gate = gate;
        this.jobs = Objects.requireNonNull(jobs, "jobs cannot be null");
        this.attempts = attempts != null ? attempts : new InMemoryRetryAttemptRepository();
        this.validations = validations != null ? validations : new InMemoryValidationResultRepository();
        this.verifications = verifications != null ? verifications : new InMemoryVerificationRepository();
        this.summaryWriter = Objects.requireNonNull(summaryWriter, "summaryWriter cannot be null");
        this.feedbackBuilder = feedbackBuilder != null ? feedbackBuilder : new CorrectiveFeedbackBuilder();
        this.maxAttempts = maxAttempts > 0 ? maxAttempts : 3;
        this.verifyBlocking = verifyBlocking;
        this.workspace = workspace;
    }

    /**
     * Executes the pipeline sequentially for the given job (§6.13).
     *
     * @param job the job specification
     * @return outcome of the pipeline execution
     */
    public PipelineOutcome run(TestGenerationJob job) {
        Objects.requireNonNull(job, "job cannot be null");
        long startTime = System.currentTimeMillis();

        try (var mdc = Mdc.forJob(job)) {
            log.info("Starting pipeline execution for job {} (targetArea={}, idempotencyKey={})",
                    job.id(), job.targetArea(), job.idempotencyKey());

            // 1. Idempotency Gate claim check (§7.3)
            TestGenerationJob activeJob = job;
            if (gate != null && activeJob.idempotencyKey() != null) {
                Claim claim = gate.claim(activeJob.idempotencyKey(), JobSpec.from(activeJob));
                if (claim.isAlreadyComplete() || claim.isAlreadyRunning()) {
                    long durationMs = System.currentTimeMillis() - startTime;
                    log.info("Job {} idempotency claim status: {} — returning no-op", activeJob.id(), claim.status());
                    PipelineOutcome noopOutcome = PipelineOutcome.noop(
                            activeJob.id(),
                            "Idempotency claim: " + claim.status(),
                            durationMs
                    );
                    emitSummary(activeJob, noopOutcome, durationMs);
                    return noopOutcome;
                }
                activeJob = activeJob.withId(claim.jobId()).withLease(claim.leaseOwner(), claim.leaseEpoch());
            } else {
                Optional<JobRecord> existing = jobs.findById(activeJob.id());
                if (existing.isPresent()) {
                    activeJob = activeJob.withLease(existing.get().leaseOwner(), existing.get().leaseEpoch());
                } else {
                    // Ensure job row exists in DB for fencing
                    JobRecord initial = JobRecord.newClaimed(
                            activeJob.id(), activeJob.idempotencyKey(), activeJob.targetArea(),
                            activeJob.testDescription(), activeJob.requester(), activeJob.recordingBranch(),
                            activeJob.promptBundleHash(), activeJob.modelId(), activeJob.orchestratorVersion(),
                            activeJob.recorderVersion(), activeJob.baseCommitSha(), activeJob.leaseOwner(),
                            activeJob.leaseEpoch(), Instant.now().plus(Duration.ofMinutes(30))
                    );
                    jobs.insert(initial);
                }
            }

            // 2. Fenced writer & Lease heartbeat (§7.4)
            FencedJobWriter writer = new FencedJobWriter(activeJob.id(), activeJob.leaseEpoch(), jobs);
            try (var heartbeat = new LeaseHeartbeat(writer, Duration.ofMinutes(30), Thread.currentThread())) {

                // 3. Parse Phase
                Mdc.phase(Phase.PARSE, 0);
                writer.setState(PipelineState.PARSED);
                ParsedTestPlan plan = parsePhase.execute(activeJob);
                activeJob = activeJob.withPlan(plan);

                // 4. Redaction Phase - FAIL CLOSED (§6.7, §9.4)
                Mdc.phase(Phase.REDACT, 0);
                writer.setState(PipelineState.REDACTED);
                Redacted<ParsedTestPlan> safe = redactionPhase.execute(plan);
                jobs.recordRedaction(activeJob.id(), safe.report());

                // 5. Context Assembly Phase (§6.11)
                Mdc.phase(Phase.CONTEXT_ASSEMBLY, 0);
                writer.setState(PipelineState.CONTEXT_ASSEMBLED);
                ContextAssemblyPhase.ContextAssemblyResult assembled = contextPhase.execute(activeJob, safe.payload());
                ExistingCodeContext context = assembled.codeContext();

                // 6. Bounded Resolve + L0/L1 Retry Loop with Code-Enforced Locator Freeze (§6.6, §6.13)
                ResolvedLocatorSet accepted = ResolvedLocatorSet.empty();
                ValidationResult validation = null;
                String feedback = null;
                int attemptsUsed = 0;

                if (llm != null) {
                    writer.setState(PipelineState.RESOLVING);
                    for (int attempt = 0; attempt < maxAttempts; attempt++) {
                        attemptsUsed = attempt + 1;
                        Mdc.phase(Phase.RESOLVE, attempt);
                        Long attemptId = attempts.begin(activeJob.id(), attempt, Phase.RESOLVE);


                        ResolvedLocatorSet proposed = llm.resolveLocators(
                                new ResolveRequest(activeJob, safe.payload(), context, feedback, attempt, attemptId));

                        // Enforce the freeze in CODE — the prompt instruction is only a hint (§6.13)
                        ResolvedLocatorSet merged = accepted.mergeKeepingAccepted(proposed);

                        validation = ValidationResult.merge(
                                grammarValidator.validate(merged, safe.payload()),       // L0
                                staticValidator.validate(merged, safe.payload()));       // L1

                        validations.saveAll(attemptId, validation);
                        attempts.finish(attemptId, validation.passed());

                        accepted = merged.retainingPassing(validation);

                        if (validation.passed()) {
                            log.info("Locator validation PASSED on attempt {}", attempt);
                            break;
                        }
                        feedback = feedbackBuilder.build(validation.blocking(), accepted);
                    }
                }

                // Escalation if validation exhausted
                if (validation != null && !validation.passed()) {
                    long durationMs = System.currentTimeMillis() - startTime;
                    log.warn("Validation exhausted after {} attempts for job {} — escalating to draft MR",
                            attemptsUsed, activeJob.id());
                    writer.setState(PipelineState.NEEDS_HUMAN_REVIEW);
                    if (gitLab != null) {
                        gitLab.publishEscalation(activeJob, null, ConfidenceTier.STATIC_ONLY,
                                "VALIDATION_EXHAUSTED", accepted, validation, attemptsUsed);
                    }
                    List<String> blockingCodes = validation.blocking().stream()
                            .map(com.company.testgen.model.ValidationViolation::reasonCode)
                            .toList();
                    PipelineOutcome escalatedOutcome = PipelineOutcome.escalated(
                            activeJob.id(), "VALIDATION_EXHAUSTED", accepted, List.of(), durationMs,
                            Math.max(1, attemptsUsed), new TokenUsage(0, 0, 0, 0), blockingCodes);
                    emitSummary(activeJob, escalatedOutcome, durationMs);
                    return escalatedOutcome;
                }

                // 7. Concurrency Control (TargetAreaLock) and HEAD SHA Re-check (§6.13, FR-26, FR-27)
                List<GeneratedFile> files = List.of();
                ConfidenceTier tier = ConfidenceTier.STATIC_ONLY;
                VerificationResult verify = null;

                if (generatePhase != null) {
                    writer.setState(PipelineState.GENERATING);
                    String baseSha = (gitLab != null) ? gitLab.headSha("main") : activeJob.baseCommitSha();

                    try (var lock = areaLock.acquire(activeJob.targetArea())) {
                        // Re-check main HEAD SHA before generating and committing (FR-27)
                        if (gitLab != null && baseSha != null && !baseSha.equals(gitLab.headSha("main"))) {
                            log.info("main HEAD SHA changed (was '{}', now '{}') — re-analyzing target area '{}'",
                                    baseSha, gitLab.headSha("main"), activeJob.targetArea());
                            context = contextPhase.execute(activeJob, safe.payload()).codeContext();
                            baseSha = gitLab.headSha("main");
                        }

                        try {
                            files = generatePhase.execute(safe.payload(), accepted, context, baseSha);
                        } catch (EscalationRequired er) {
                            long durationMs = System.currentTimeMillis() - startTime;
                            log.warn("Generate phase required escalation: {} ({})", er.reasonCode(), er.report());
                            writer.setState(PipelineState.NEEDS_HUMAN_REVIEW);
                            if (gitLab != null) {
                                gitLab.publishEscalation(activeJob, null, ConfidenceTier.STATIC_ONLY,
                                        er.reasonCode(), accepted, validation, attemptsUsed);
                            }
                            PipelineOutcome escalatedOutcome = PipelineOutcome.escalated(
                                    activeJob.id(), er.reasonCode(), accepted, List.of(), durationMs);
                            emitSummary(activeJob, escalatedOutcome, durationMs);
                            return escalatedOutcome;
                        }

                        // 8. L2 Dynamic Verification Phase (§6.8)
                        if (verifyPhase != null && workspace != null) {
                            writer.setState(PipelineState.VERIFYING);
                            verify = verifyPhase.execute(activeJob, files, workspace);
                            verifications.save(activeJob.id(), verify);

                            if (verify.isFailure() && verifyBlocking) {
                                long durationMs = System.currentTimeMillis() - startTime;
                                log.warn("L2 verification failed blocking gate for job {} — escalating", activeJob.id());
                                writer.setState(PipelineState.NEEDS_HUMAN_REVIEW);
                                if (gitLab != null) {
                                    gitLab.publishEscalation(activeJob, files, ConfidenceTier.STATIC_ONLY,
                                            "VERIFY_FAILED", accepted, validation, attemptsUsed);
                                }
                                PipelineOutcome escalatedOutcome = PipelineOutcome.escalated(
                                        activeJob.id(), "VERIFY_FAILED", accepted, files, durationMs);
                                emitSummary(activeJob, escalatedOutcome, durationMs);
                                return escalatedOutcome;
                            }
                        }

                        tier = ConfidenceTier.from(verify, safe.payload());

                        // 9. GitLab Publication (§6.15, §7.5)
                        MergeRequestResult mr = null;
                        if (gitLab != null) {
                            writer.setState(PipelineState.PUBLISHING);
                            mr = gitLab.publish(
                                    activeJob,
                                    files,
                                    tier,
                                    verify,
                                    accepted,
                                    context,
                                    safe.report(),
                                    null,
                                    Duration.ofMillis(System.currentTimeMillis() - startTime),
                                    Math.max(1, attemptsUsed),
                                    BigDecimal.ZERO
                            );
                        }

                        // Mark complete with fencing
                        writer.complete(tier.name(), mr != null ? mr.iid() : null, mr != null ? mr.webUrl() : null);
                    }
                } else {
                    tier = ConfidenceTier.from(null, safe.payload());
                }

                long durationMs = System.currentTimeMillis() - startTime;
                log.info("Pipeline completed successfully in {}ms for job {} with tier {}",
                        durationMs, activeJob.id(), tier);

                PipelineOutcome outcome = PipelineOutcome.success(
                        activeJob.id(),
                        tier.name(),
                        accepted,
                        files,
                        durationMs,
                        Math.max(1, attemptsUsed),
                        new TokenUsage(0, 0, 0, 0)
                );
                emitSummary(activeJob, outcome, durationMs);
                return outcome;
            }

        } catch (LeaseLostException lle) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("Lease lost during pipeline execution: {}", lle.getMessage(), lle);
            PipelineOutcome outcome = PipelineOutcome.failure(
                    job.id(),
                    "LEASE_LOST: " + lle.getMessage(),
                    PipelineOutcome.EXIT_LEASE_LOST,
                    durationMs
            );
            emitSummary(job, outcome, durationMs);
            return outcome;

        } catch (BudgetExceededException bee) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("Budget exceeded: {}", bee.getMessage(), bee);
            jobs.markFailed(job.id(), "BUDGET_EXCEEDED: " + bee.getMessage());
            PipelineOutcome outcome = PipelineOutcome.failure(
                    job.id(),
                    "BUDGET_EXCEEDED: " + bee.getMessage(),
                    PipelineOutcome.EXIT_BUDGET_EXCEEDED,
                    durationMs
            );
            emitSummary(job, outcome, durationMs);
            return outcome;

        } catch (RedactionFailedException rfe) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("Redaction phase failed - aborting fail-closed: {}", rfe.getMessage(), rfe);
            jobs.markFailed(job.id(), "REDACTION_FAILED: " + rfe.getMessage());

            PipelineOutcome outcome = PipelineOutcome.failure(
                    job.id(),

                    "REDACTION_FAILED: " + rfe.getMessage(),
                    PipelineOutcome.EXIT_REDACTION_FAILURE,
                    durationMs
            );
            emitSummary(job, outcome, durationMs);
            return outcome;

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("Pipeline failed with unexpected error: {}", e.getMessage(), e);
            jobs.markFailed(job.id(), e.getMessage());

            PipelineOutcome outcome = PipelineOutcome.failure(
                    job.id(),
                    e.getMessage(),
                    PipelineOutcome.EXIT_INFRA_FAILURE,
                    durationMs
            );
            emitSummary(job, outcome, durationMs);
            return outcome;
        }
    }

    private void emitSummary(TestGenerationJob job, PipelineOutcome outcome, long durationMs) {
        try {
            JobSummary summary = new JobSummary(
                    job.id() != null ? job.id().toString() : null,
                    job.idempotencyKey(),
                    outcome.state(),
                    outcome.confidenceTier(),
                    job.targetArea(),
                    1,
                    outcome.escalated(),
                    outcome.failureReason(),
                    List.of(),
                    new JobSummary.VerificationSummary(outcome.state().equals("DONE") ? "PASSED" : "FAILED", durationMs),
                    new JobSummary.CostSummary(0, 0, 0, BigDecimal.ZERO),
                    new JobSummary.ProvenanceSummary(
                            job.modelId(),
                            job.promptBundleHash(),
                            job.orchestratorVersion(),
                            job.baseCommitSha()
                    ),
                    null,
                    durationMs
            );
            summaryWriter.write(summary);
        } catch (Exception e) {
            log.error("Failed to write job summary artifact: {}", e.getMessage(), e);
        }
    }

    public ParsePhase parsePhase() {
        return parsePhase;
    }

    public RedactionPhase redactionPhase() {
        return redactionPhase;
    }

    public ContextAssemblyPhase contextPhase() {
        return contextPhase;
    }

    public JobRepository jobRepository() {
        return jobs;
    }

    public JobSummaryWriter summaryWriter() {
        return summaryWriter;
    }
}
