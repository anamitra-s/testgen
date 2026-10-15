package com.company.testgen.persistence;

import com.company.testgen.model.Phase;
import org.jdbi.v3.core.Handle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for managing retry attempts in {@code retry_attempts} (§6.13, §6.14).
 */
public interface RetryAttemptRepository {

    /**
     * Begins a new attempt for a job and phase (§6.13).
     *
     * @return the auto-generated attempt ID
     */
    Long begin(UUID jobId, int attemptNo, Phase phase);

    Long begin(UUID jobId, int attemptNo, String phase);

    Long begin(Handle handle, UUID jobId, int attemptNo, String phase);

    /**
     * Marks an attempt finished with its pass/fail outcome (§6.13).
     */
    void finish(Long attemptId, boolean passed);

    void finish(Long attemptId, boolean passed, String feedbackSent);

    void finish(Handle handle, Long attemptId, boolean passed, String feedbackSent);

    Optional<RetryAttemptRecord> findById(Long id);

    Optional<RetryAttemptRecord> findById(Handle handle, Long id);

    Optional<RetryAttemptRecord> find(UUID jobId, int attemptNo, String phase);

    Optional<RetryAttemptRecord> find(Handle handle, UUID jobId, int attemptNo, String phase);

    List<RetryAttemptRecord> findByJobId(UUID jobId);

    List<RetryAttemptRecord> findByJobId(Handle handle, UUID jobId);
}
