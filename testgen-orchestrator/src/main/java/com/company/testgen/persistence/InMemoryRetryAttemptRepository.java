package com.company.testgen.persistence;

import com.company.testgen.model.Phase;
import org.jdbi.v3.core.Handle;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory test double for {@link RetryAttemptRepository}.
 */
public class InMemoryRetryAttemptRepository implements RetryAttemptRepository {

    private final AtomicLong sequence = new AtomicLong(1L);
    private final Map<Long, RetryAttemptRecord> storage = new ConcurrentHashMap<>();

    @Override
    public Long begin(UUID jobId, int attemptNo, Phase phase) {
        return begin(jobId, attemptNo, phase != null ? phase.name() : null);
    }

    @Override
    public Long begin(UUID jobId, int attemptNo, String phase) {
        return begin(null, jobId, attemptNo, phase);
    }

    @Override
    public Long begin(Handle handle, UUID jobId, int attemptNo, String phase) {
        long id = sequence.getAndIncrement();
        RetryAttemptRecord record = new RetryAttemptRecord(
                id, jobId, attemptNo, phase, null, null, Instant.now(), null
        );
        storage.put(id, record);
        return id;
    }

    @Override
    public void finish(Long attemptId, boolean passed) {
        finish(attemptId, passed, null);
    }

    @Override
    public void finish(Long attemptId, boolean passed, String feedbackSent) {
        finish(null, attemptId, passed, feedbackSent);
    }

    @Override
    public void finish(Handle handle, Long attemptId, boolean passed, String feedbackSent) {
        storage.computeIfPresent(attemptId, (id, r) -> new RetryAttemptRecord(
                r.id(), r.jobId(), r.attemptNo(), r.phase(), passed, feedbackSent, r.startedAt(), Instant.now()
        ));
    }

    @Override
    public Optional<RetryAttemptRecord> findById(Long id) {
        return Optional.ofNullable(storage.get(id));
    }

    @Override
    public Optional<RetryAttemptRecord> findById(Handle handle, Long id) {
        return findById(id);
    }

    @Override
    public Optional<RetryAttemptRecord> find(UUID jobId, int attemptNo, String phase) {
        return find(null, jobId, attemptNo, phase);
    }

    @Override
    public Optional<RetryAttemptRecord> find(Handle handle, UUID jobId, int attemptNo, String phase) {
        return storage.values().stream()
                .filter(r -> r.jobId().equals(jobId) && r.attemptNo() == attemptNo && r.phase().equalsIgnoreCase(phase))
                .findFirst();
    }

    @Override
    public List<RetryAttemptRecord> findByJobId(UUID jobId) {
        return findByJobId(null, jobId);
    }

    @Override
    public List<RetryAttemptRecord> findByJobId(Handle handle, UUID jobId) {
        return storage.values().stream()
                .filter(r -> r.jobId().equals(jobId))
                .sorted(Comparator.comparingInt(RetryAttemptRecord::attemptNo))
                .toList();
    }

    public void clear() {
        storage.clear();
    }
}
