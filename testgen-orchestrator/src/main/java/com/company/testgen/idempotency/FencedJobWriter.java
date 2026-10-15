package com.company.testgen.idempotency;

import com.company.testgen.orchestrator.PipelineState;
import com.company.testgen.persistence.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Mutates job state and metadata strictly protected by lease epoch fencing (§7.4).
 * Every mutation executes:
 * {@code WHERE id = :id AND lease_epoch = :epoch}
 * If zero rows are affected, {@link LeaseLostException} is thrown to immediately abort the process.
 */
public class FencedJobWriter {

    private static final Logger log = LoggerFactory.getLogger(FencedJobWriter.class);
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final UUID jobId;
    private final long leaseEpoch;
    private final JobRepository jobs;
    private final Duration ttl;

    public FencedJobWriter(UUID jobId, long leaseEpoch, JobRepository jobs, Duration ttl) {
        this.jobId = Objects.requireNonNull(jobId, "jobId cannot be null");
        this.leaseEpoch = leaseEpoch;
        this.jobs = Objects.requireNonNull(jobs, "jobs cannot be null");
        this.ttl = ttl != null ? ttl : DEFAULT_TTL;
    }

    public FencedJobWriter(UUID jobId, long leaseEpoch, JobRepository jobs) {
        this(jobId, leaseEpoch, jobs, DEFAULT_TTL);
    }

    public UUID getJobId() {
        return jobId;
    }

    public long getLeaseEpoch() {
        return leaseEpoch;
    }

    public Duration getTtl() {
        return ttl;
    }

    /**
     * Updates pipeline state with lease fencing.
     */
    public void setState(PipelineState state) {
        Objects.requireNonNull(state, "state cannot be null");
        setState(state.name());
    }

    /**
     * Updates pipeline state with lease fencing.
     */
    public void setState(String state) {
        Objects.requireNonNull(state, "state cannot be null");
        Instant expiresAt = Instant.now().plus(ttl);
        boolean updated = jobs.updateStateFenced(jobId, state, leaseEpoch, expiresAt);
        if (!updated) {
            log.error("Fenced setState rejected for job {} at epoch {}: lease lost", jobId, leaseEpoch);
            throw new LeaseLostException(jobId, leaseEpoch);
        }
        log.debug("Updated job {} state to {} (epoch {})", jobId, state, leaseEpoch);
    }

    /**
     * Extends lease expiration time with lease fencing.
     */
    public void touch() {
        Instant expiresAt = Instant.now().plus(ttl);
        boolean updated = jobs.touch(jobId, leaseEpoch, expiresAt);
        if (!updated) {
            log.error("Fenced touch rejected for job {} at epoch {}: lease lost", jobId, leaseEpoch);
            throw new LeaseLostException(jobId, leaseEpoch);
        }
        log.debug("Heartbeat touch successful for job {} (epoch {}, expiresAt {})", jobId, leaseEpoch, expiresAt);
    }

    /**
     * Completes job successfully with lease fencing.
     */
    public void complete(String confidenceTier, Integer mrIid, String mrUrl) {
        boolean updated = jobs.completeFenced(jobId, leaseEpoch, confidenceTier, mrIid, mrUrl);
        if (!updated) {
            log.error("Fenced complete rejected for job {} at epoch {}: lease lost", jobId, leaseEpoch);
            throw new LeaseLostException(jobId, leaseEpoch);
        }
        log.info("Job {} completed successfully with MR {} (epoch {})", jobId, mrUrl, leaseEpoch);
    }

    /**
     * Marks job failed with lease fencing.
     */
    public void fail(String failureReason) {
        boolean updated = jobs.failFenced(jobId, leaseEpoch, failureReason);
        if (!updated) {
            log.error("Fenced fail rejected for job {} at epoch {}: lease lost", jobId, leaseEpoch);
            throw new LeaseLostException(jobId, leaseEpoch);
        }
        log.warn("Job {} marked failed: {} (epoch {})", jobId, failureReason, leaseEpoch);
    }
}
