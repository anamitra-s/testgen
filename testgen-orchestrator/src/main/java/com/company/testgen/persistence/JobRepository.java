package com.company.testgen.persistence;

import com.company.testgen.model.JobRecord;
import com.company.testgen.model.RedactionReport;
import org.jdbi.v3.core.Handle;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for CRUD operations and concurrency/lease management on the {@code jobs} table.
 * Hand-crafted queries via JDBI 3; no ORM / JPA (§6.14, §7.3, §7.4, §9.7).
 */
public interface JobRepository {

    /**
     * Inserts a new job record.
     */
    void insert(JobRecord job);

    /**
     * Inserts a new job record within the given JDBI handle/transaction.
     */
    void insert(Handle handle, JobRecord job);

    /**
     * Finds a job by its unique UUID identifier.
     */
    Optional<JobRecord> findById(UUID id);

    /**
     * Finds a job by its unique UUID identifier within the given handle.
     */
    Optional<JobRecord> findById(Handle handle, UUID id);

    /**
     * Finds a job by its unique content-derived idempotency key (§7.2, §7.3).
     */
    Optional<JobRecord> findByIdempotencyKey(String idempotencyKey);

    /**
     * Finds a job by its unique content-derived idempotency key within the given handle.
     */
    Optional<JobRecord> findByIdempotencyKey(Handle handle, String idempotencyKey);

    /**
     * Updates an existing job record.
     * @return true if updated, false if not found
     */
    boolean update(JobRecord job);

    /**
     * Updates an existing job record within the given handle.
     * @return true if updated, false if not found
     */
    boolean update(Handle handle, JobRecord job);

    /**
     * Deletes a job by its UUID.
     * @return true if deleted, false if not found
     */
    boolean deleteById(UUID id);

    /**
     * Deletes a job by its UUID within the given handle.
     * @return true if deleted, false if not found
     */
    boolean deleteById(Handle handle, UUID id);

    /**
     * Returns all jobs ordered by creation timestamp descending.
     */
    List<JobRecord> findAll();

    /**
     * Returns all jobs ordered by creation timestamp descending within the given handle.
     */
    List<JobRecord> findAll(Handle handle);

    /**
     * Finds all jobs currently in the specified state.
     */
    List<JobRecord> findByState(String state);

    /**
     * Finds all jobs currently in the specified state within the given handle.
     */
    List<JobRecord> findByState(Handle handle, String state);

    /**
     * Finds all jobs for a specific target area.
     */
    List<JobRecord> findByTargetArea(String targetArea);

    /**
     * Finds all jobs for a specific target area within the given handle.
     */
    List<JobRecord> findByTargetArea(Handle handle, String targetArea);

    /**
     * Atomic reclaim: bumps lease_epoch, assigns new owner and expiration if the lease is stale or failed (§7.3).
     *
     * @return newly assigned lease_epoch if reclaim succeeded, empty if job was not eligible for reclaim
     */
    Optional<Long> reclaim(UUID id, String owner, Instant expiresAt);

    /**
     * Atomic reclaim within the given handle (§7.3).
     */
    Optional<Long> reclaim(Handle handle, UUID id, String owner, Instant expiresAt);

    /**
     * Fenced state transition: verifies lease_epoch before updating state (§7.4).
     *
     * @return true if epoch matched and update succeeded, false if lease was lost to another process
     */
    boolean updateStateFenced(UUID id, String state, long leaseEpoch, Instant expiresAt);

    /**
     * Fenced state transition within the given handle (§7.4).
     */
    boolean updateStateFenced(Handle handle, UUID id, String state, long leaseEpoch, Instant expiresAt);

    /**
     * Marks a job failed with a failure reason.
     */
    void markFailed(UUID id, String failureReason);

    /**
     * Marks a job failed with a failure reason within the given handle.
     */
    void markFailed(Handle handle, UUID id, String failureReason);

    /**
     * Records the redaction report for the given job (§6.13).
     */
    void recordRedaction(UUID id, RedactionReport report);

    /**
     * Records the redaction report JSON for the given job within a handle (§6.13).
     */
    void recordRedaction(Handle handle, UUID id, String reportJson);
}
