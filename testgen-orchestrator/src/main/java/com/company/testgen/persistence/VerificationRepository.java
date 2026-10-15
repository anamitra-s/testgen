package com.company.testgen.persistence;

import com.company.testgen.model.VerificationResult;
import org.jdbi.v3.core.Handle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for recording and querying verification runs in {@code verification_runs} (§6.8, §6.13, §6.14).
 */
public interface VerificationRepository {

    /**
     * Appends an L2 verification result row for the given job (§6.8, §6.13, §7.6).
     */
    void save(UUID jobId, VerificationResult result);

    void save(Handle handle, UUID jobId, VerificationResult result);

    Optional<VerificationRecord> findLatestByJobId(UUID jobId);

    Optional<VerificationRecord> findLatestByJobId(Handle handle, UUID jobId);

    List<VerificationRecord> findByJobId(UUID jobId);

    List<VerificationRecord> findByJobId(Handle handle, UUID jobId);

    List<VerificationRecord> findAll();

    List<VerificationRecord> findAll(Handle handle);
}
