package com.company.testgen.idempotency;

import com.company.testgen.model.JobRecord;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.persistence.Db;
import com.company.testgen.persistence.JobRepository;
import org.jdbi.v3.core.Handle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Claim protocol gate enforcing job-level idempotency (§7.3).
 * Supports PROCEED, RESUME, ALREADY_COMPLETE, and ALREADY_RUNNING outcomes
 * using transactional queries on jobs and atomic lease_epoch increments on reclaim.
 */
public class IdempotencyGate {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGate.class);
    private static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(30);

    private final Db db;
    private final JobRepository jobs;
    private final String leaseOwner;
    private final Duration leaseTtl;

    public IdempotencyGate(Db db, JobRepository jobs, String leaseOwner, Duration leaseTtl) {
        this.db = db;
        this.jobs = Objects.requireNonNull(jobs, "jobs cannot be null");
        this.leaseOwner = leaseOwner != null ? leaseOwner : defaultLeaseOwner();
        this.leaseTtl = leaseTtl != null ? leaseTtl : DEFAULT_LEASE_TTL;
    }

    public IdempotencyGate(JobRepository jobs, String leaseOwner, Duration leaseTtl) {
        this(null, jobs, leaseOwner, leaseTtl);
    }

    public IdempotencyGate(Db db, JobRepository jobs) {
        this(db, jobs, null, null);
    }

    public IdempotencyGate(JobRepository jobs) {
        this(null, jobs, null, null);
    }

    /**
     * Asserts that the client-provided key matches the content-derived key (§7.2).
     *
     * @param cliKey client-provided idempotency key (may be null/blank if omitted)
     * @param derivedKey content-derived idempotency key
     * @throws IllegalArgumentException if cliKey is provided and does not match derivedKey
     */
    public static void assertKeyMatches(String cliKey, String derivedKey) {
        if (cliKey != null && !cliKey.isBlank()) {
            if (!cliKey.trim().equals(derivedKey)) {
                throw new IllegalArgumentException(
                        "Idempotency key mismatch: CLI provided '" + cliKey.trim() + "' but derived '" + derivedKey + "'");
            }
        }
    }

    public Claim claim(String idempotencyKey, JobSpec spec) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        Objects.requireNonNull(spec, "spec cannot be null");

        if (db != null) {
            return db.inTransaction(h -> doClaim(h, idempotencyKey, spec));
        } else {
            return doClaim(null, idempotencyKey, spec);
        }
    }

    public Claim claim(String idempotencyKey, TestGenerationJob job) {
        return claim(idempotencyKey, JobSpec.from(job));
    }

    public Claim claim(TestGenerationJob job) {
        Objects.requireNonNull(job, "job cannot be null");
        return claim(job.idempotencyKey(), JobSpec.from(job));
    }

    public Claim claim(String idempotencyKey, JobRecord record) {
        return claim(idempotencyKey, JobSpec.from(record));
    }

    public Claim claim(JobRecord record) {
        Objects.requireNonNull(record, "record cannot be null");
        return claim(record.idempotencyKey(), JobSpec.from(record));
    }

    private Claim doClaim(Handle handle, String idempotencyKey, JobSpec spec) {
        Optional<JobRecord> existing = handle != null
                ? jobs.findByIdempotencyKey(handle, idempotencyKey)
                : jobs.findByIdempotencyKey(idempotencyKey);

        if (existing.isEmpty()) {
            UUID id = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(leaseTtl);
            long epoch = 1L;
            String buildUrl = System.getenv("BUILD_URL");
            JobRecord newJob = JobRecord.newClaimed(
                    id,
                    idempotencyKey,
                    spec.targetArea(),
                    spec.testDescription(),
                    spec.requester(),
                    spec.recordingBranch(),
                    spec.promptBundleHash(),
                    spec.modelId(),
                    spec.orchestratorVersion(),
                    spec.recorderVersion(),
                    spec.baseCommitSha(),
                    leaseOwner,
                    epoch,
                    expiresAt,
                    buildUrl
            );

            try {
                if (handle != null) {
                    jobs.insert(handle, newJob);
                } else {
                    jobs.insert(newJob);
                }
                log.info("Claimed new job {} with idempotency key {} (epoch 1)", id, idempotencyKey);
                return Claim.proceed(id, epoch);
            } catch (Exception e) {
                // Unique constraint collision on idempotency_key race: fall back to existing lookup
                log.debug("Concurrent insert collision on idempotency key '{}', fetching existing: {}",
                        idempotencyKey, e.getMessage());
                existing = handle != null
                        ? jobs.findByIdempotencyKey(handle, idempotencyKey)
                        : jobs.findByIdempotencyKey(idempotencyKey);
                if (existing.isEmpty()) {
                    throw e;
                }
            }
        }

        JobRecord job = existing.get();
        String rawState = job.state() != null ? job.state().trim().toUpperCase(Locale.ROOT) : "";
        Instant now = Instant.now();

        return switch (rawState) {
            // Terminal & successful: this exact recording already produced an MR
            case "DONE", "NEEDS_HUMAN_REVIEW" -> {
                log.info("Job {} already complete in state {} (mrUrl={})", job.id(), job.state(), job.mrUrl());
                yield Claim.alreadyComplete(job.id(), job.mrUrl(), job.state());
            }

            // Terminal & failed: a rebuild is a legitimate request to try again
            case "FAILED" -> {
                Instant expiresAt = now.plus(leaseTtl);
                Optional<Long> epochOpt = handle != null
                        ? jobs.reclaim(handle, job.id(), leaseOwner, expiresAt)
                        : jobs.reclaim(job.id(), leaseOwner, expiresAt);
                long epoch = epochOpt.orElseThrow(() ->
                        new IllegalStateException("Failed to reclaim FAILED job " + job.id()));
                log.info("Reclaimed FAILED job {} with new lease epoch {}", job.id(), epoch);
                yield Claim.proceed(job.id(), epoch);
            }

            // In flight
            default -> {
                if (job.leaseExpiresAt() != null && job.leaseExpiresAt().isAfter(now)) {
                    log.info("Job {} is already in flight on {} until {}",
                            job.id(), job.leaseOwner(), job.leaseExpiresAt());
                    yield Claim.alreadyRunning(job.id(), job.leaseOwner(), job.leaseExpiresAt());
                }

                Instant expiresAt = now.plus(leaseTtl);
                Optional<Long> epochOpt = handle != null
                        ? jobs.reclaim(handle, job.id(), leaseOwner, expiresAt)
                        : jobs.reclaim(job.id(), leaseOwner, expiresAt);

                if (epochOpt.isEmpty()) {
                    // Reclaim lost to concurrent reclaimer
                    Optional<JobRecord> current = handle != null
                            ? jobs.findById(handle, job.id())
                            : jobs.findById(job.id());
                    if (current.isPresent() && current.get().leaseExpiresAt() != null
                            && current.get().leaseExpiresAt().isAfter(now)) {
                        yield Claim.alreadyRunning(current.get().id(), current.get().leaseOwner(), current.get().leaseExpiresAt());
                    }
                    throw new IllegalStateException("Failed to reclaim expired lease for job " + job.id());
                }

                long epoch = epochOpt.get();
                log.warn("Reclaimed stale lease for job {} from {} (expired {}), epoch now {}",
                        job.id(), job.leaseOwner(), job.leaseExpiresAt(), epoch);
                yield Claim.resume(job.id(), epoch, job.state());
            }
        };
    }

    public String leaseOwner() {
        return leaseOwner;
    }

    public Duration leaseTtl() {
        return leaseTtl;
    }

    private static String defaultLeaseOwner() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            host = System.getenv("COMPUTERNAME");
        }
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception ignored) {
                host = "localhost";
            }
        }
        return host + ":" + ProcessHandle.current().pid();
    }
}
