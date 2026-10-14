package com.company.testgen.orchestrator;

import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.Phase;
import com.company.testgen.model.Redacted;
import com.company.testgen.model.RedactionReport;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.observability.JobSummary;
import com.company.testgen.observability.JobSummaryWriter;
import com.company.testgen.observability.Mdc;
import com.company.testgen.persistence.JobRepository;
import com.company.testgen.phase.ContextAssemblyPhase;
import com.company.testgen.phase.ParsePhase;
import com.company.testgen.phase.RedactionPhase;
import com.company.testgen.redaction.RedactionFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Core orchestrator executing test generation pipeline phases sequentially (§6.13, §9.3).
 * Manages MDC context, fail-closed redaction with DB persistence, and emits job-summary.json artifacts.
 */
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private final ParsePhase parsePhase;
    private final RedactionPhase redactionPhase;
    private final ContextAssemblyPhase contextPhase;
    private final JobRepository jobs;
    private final JobSummaryWriter summaryWriter;

    public PipelineOrchestrator(
            ParsePhase parsePhase,
            RedactionPhase redactionPhase,
            ContextAssemblyPhase contextPhase,
            JobRepository jobs,
            JobSummaryWriter summaryWriter
    ) {
        this.parsePhase = Objects.requireNonNull(parsePhase, "parsePhase cannot be null");
        this.redactionPhase = Objects.requireNonNull(redactionPhase, "redactionPhase cannot be null");
        this.contextPhase = Objects.requireNonNull(contextPhase, "contextPhase cannot be null");
        this.jobs = Objects.requireNonNull(jobs, "jobs cannot be null");
        this.summaryWriter = Objects.requireNonNull(summaryWriter, "summaryWriter cannot be null");
    }

    /**
     * Executes the pipeline sequentially for the given job.
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

            // 1. Parse Phase
            Mdc.phase(Phase.PARSE, 0);
            ParsedTestPlan plan = parsePhase.execute(job);

            // 2. Redaction Phase - FAIL CLOSED
            Mdc.phase(Phase.REDACT, 0);
            Redacted<ParsedTestPlan> safe = redactionPhase.execute(plan);
            jobs.recordRedaction(job.id(), safe.report());

            // 3. Context Assembly Phase (stubbed)
            Mdc.phase(Phase.CONTEXT_ASSEMBLY, 0);
            ExistingCodeContext context = contextPhase.execute(job.targetArea());

            long durationMs = System.currentTimeMillis() - startTime;
            log.info("Pipeline stages PARSE, REDACT, and CONTEXT_ASSEMBLY completed in {}ms for job {}",
                    durationMs, job.id());

            PipelineOutcome outcome = PipelineOutcome.success(
                    job.id(),
                    "VERIFIED",
                    new ResolvedLocatorSet(List.of()),
                    List.of(),
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
