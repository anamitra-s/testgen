package com.company.testgen.persistence;

import com.company.testgen.model.VerificationResult;
import org.jdbi.v3.core.Handle;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory test double for {@link VerificationRepository}.
 */
public class InMemoryVerificationRepository implements VerificationRepository {

    private final AtomicLong sequence = new AtomicLong(1L);
    private final List<VerificationRecord> storage = new CopyOnWriteArrayList<>();

    @Override
    public void save(UUID jobId, VerificationResult result) {
        save(null, jobId, result);
    }

    @Override
    public void save(Handle handle, UUID jobId, VerificationResult result) {
        if (result == null || jobId == null) {
            return;
        }
        long id = sequence.getAndIncrement();
        VerificationRecord record = new VerificationRecord(
                id,
                jobId,
                result.outcome(),
                result.skipReason(),
                result.durationMs(),
                result.failureTail(),
                result.blocking(),
                Instant.now()
        );
        storage.add(record);
    }

    @Override
    public Optional<VerificationRecord> findLatestByJobId(UUID jobId) {
        return findLatestByJobId(null, jobId);
    }

    @Override
    public Optional<VerificationRecord> findLatestByJobId(Handle handle, UUID jobId) {
        return storage.stream()
                .filter(r -> r.jobId().equals(jobId))
                .max(Comparator.comparing(VerificationRecord::id));
    }

    @Override
    public List<VerificationRecord> findByJobId(UUID jobId) {
        return findByJobId(null, jobId);
    }

    @Override
    public List<VerificationRecord> findByJobId(Handle handle, UUID jobId) {
        return storage.stream()
                .filter(r -> r.jobId().equals(jobId))
                .sorted(Comparator.comparing(VerificationRecord::id))
                .toList();
    }

    @Override
    public List<VerificationRecord> findAll() {
        return findAll(null);
    }

    @Override
    public List<VerificationRecord> findAll(Handle handle) {
        return storage.stream()
                .sorted(Comparator.comparing(VerificationRecord::id).reversed())
                .toList();
    }

    public void clear() {
        storage.clear();
    }
}
