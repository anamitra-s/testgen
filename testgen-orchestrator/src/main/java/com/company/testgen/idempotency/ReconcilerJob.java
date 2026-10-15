package com.company.testgen.idempotency;

import com.company.testgen.model.JobRecord;
import com.company.testgen.persistence.EffectRecord;
import com.company.testgen.persistence.EffectRepository;
import com.company.testgen.persistence.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Scheduled background sweep (e.g. cron 0/15 * * * *) repairing non-terminal states (§7.7).
 *
 * 1. Jobs whose lease expired (> 45 min) but never reached a terminal state are marked FAILED ("ORPHANED_LEASE")
 *    and an alert is raised.
 * 2. PENDING effects older than the grace window (30 min) are reconciled against GitLab.
 * 3. Invariant audit: jobs marked DONE without a CONFIRMED MERGE_REQUEST effect trigger an immediate Ops alert.
 */
public class ReconcilerJob {

    private static final Logger log = LoggerFactory.getLogger(ReconcilerJob.class);

    private final JobRepository jobs;
    private final EffectRepository effects;
    private final GitLabLookup gitLab;
    private final OpsAlerter alerter;
    private final Duration orphanLeaseAge;
    private final Duration pendingEffectAge;

    public ReconcilerJob(JobRepository jobs, EffectRepository effects, GitLabLookup gitLab, OpsAlerter alerter,
                         Duration orphanLeaseAge, Duration pendingEffectAge) {
        this.jobs = Objects.requireNonNull(jobs, "jobs cannot be null");
        this.effects = Objects.requireNonNull(effects, "effects cannot be null");
        this.gitLab = gitLab != null ? gitLab : (type, key) -> Optional.empty();
        this.alerter = alerter != null ? alerter : msg -> log.warn("OpsAlert: {}", msg);
        this.orphanLeaseAge = orphanLeaseAge != null ? orphanLeaseAge : Duration.ofMinutes(45);
        this.pendingEffectAge = pendingEffectAge != null ? pendingEffectAge : Duration.ofMinutes(30);
    }

    public ReconcilerJob(JobRepository jobs, EffectRepository effects, GitLabLookup gitLab, OpsAlerter alerter) {
        this(jobs, effects, gitLab, alerter, Duration.ofMinutes(45), Duration.ofMinutes(30));
    }

    public ReconcilerJob(JobRepository jobs, EffectRepository effects) {
        this(jobs, effects, (type, key) -> Optional.empty(), msg -> log.warn("OpsAlert: {}", msg));
    }

    public ReconcileReport run() {
        log.info("Starting Reconciler sweep...");

        // 1. Jobs whose lease expired but which never reached a terminal state
        List<JobRecord> orphans = jobs.findExpiredNonTerminal(orphanLeaseAge);
        for (JobRecord j : orphans) {
            jobs.markFailed(j.id(), "ORPHANED_LEASE");
            String alert = String.format("Job %s orphaned in state %s — rebuild %s to resume",
                    j.id(), j.state(), j.jenkinsBuildUrl());
            alerter.alertOps(alert);
            log.warn("Marked job {} as ORPHANED_LEASE (previous state: {})", j.id(), j.state());
        }

        // 2. PENDING effects older than the grace window: reconcile against GitLab
        int reconciled = 0;
        int abandoned = 0;
        List<EffectRecord> pending = effects.findPendingOlderThan(pendingEffectAge);
        for (EffectRecord e : pending) {
            Optional<String> external = gitLab.lookup(e.effectType(), e.effectKey());
            if (external.isPresent()) {
                effects.confirm(e, external.get());
                reconciled++;
                log.info("Reconciled aged PENDING effect {}/{} to CONFIRMED with id {}",
                        e.effectType(), e.effectKey(), external.get());
            } else {
                effects.abandon(e, "NOT_FOUND_AFTER_GRACE");
                abandoned++;
                log.warn("Abandoned aged PENDING effect {}/{} with NOT_FOUND_AFTER_GRACE",
                        e.effectType(), e.effectKey());
            }
        }

        // 3. Consistency audit: jobs marked DONE with no CONFIRMED MERGE_REQUEST effect
        List<UUID> inconsistent = jobs.doneWithoutConfirmedMr();
        if (!inconsistent.isEmpty()) {
            String alert = String.format("⛔ INVARIANT VIOLATION: %d jobs DONE without a confirmed MR: %s",
                    inconsistent.size(), inconsistent);
            alerter.alertOps(alert);
            log.error(alert);
        }

        ReconcileReport report = new ReconcileReport(orphans.size(), reconciled, abandoned, inconsistent.size(), inconsistent);
        log.info("Reconciler sweep complete: {} orphans, {} reconciled effects, {} abandoned effects, {} invariant violations",
                report.orphanedJobsMarked(), report.pendingEffectsReconciled(), report.pendingEffectsAbandoned(), report.invariantViolations());
        return report;
    }
}
