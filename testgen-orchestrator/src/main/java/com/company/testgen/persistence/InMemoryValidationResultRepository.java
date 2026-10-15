package com.company.testgen.persistence;

import com.company.testgen.model.ValidationResult;
import com.company.testgen.model.ValidationViolation;
import org.jdbi.v3.core.Handle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory test double for {@link ValidationResultRepository}.
 */
public class InMemoryValidationResultRepository implements ValidationResultRepository {

    private final AtomicLong sequence = new AtomicLong(1L);
    private final List<ValidationResultRecord> storage = new CopyOnWriteArrayList<>();

    @Override
    public void saveAll(Long attemptId, ValidationResult validation) {
        saveAll(attemptId, "L0_L1", validation);
    }

    @Override
    public void saveAll(Long attemptId, String layer, ValidationResult validation) {
        saveAll(null, attemptId, layer, validation);
    }

    @Override
    public void saveAll(Handle handle, Long attemptId, String layer, ValidationResult validation) {
        if (validation == null || validation.violations().isEmpty()) {
            return;
        }
        for (ValidationViolation v : validation.violations()) {
            save(handle, attemptId, layer, v);
        }
    }

    @Override
    public void save(Long attemptId, String layer, ValidationViolation violation) {
        save(null, attemptId, layer, violation);
    }

    @Override
    public void save(Handle handle, Long attemptId, String layer, ValidationViolation violation) {
        long id = sequence.getAndIncrement();
        ValidationResultRecord record = new ValidationResultRecord(
                id,
                attemptId,
                layer != null ? layer : "L0_L1",
                violation.elementRefId(),
                violation.locatorExpression(),
                violation.reasonCode(),
                violation.matchCount(),
                violation.severity(),
                violation.detail()
        );
        storage.add(record);
    }

    @Override
    public List<ValidationViolation> findByAttemptId(Long attemptId) {
        return findByAttemptId(null, attemptId);
    }

    @Override
    public List<ValidationViolation> findByAttemptId(Handle handle, Long attemptId) {
        return storage.stream()
                .filter(r -> r.attemptId().equals(attemptId))
                .map(ValidationResultRecord::toViolation)
                .toList();
    }

    @Override
    public List<ValidationResultRecord> findRecordsByAttemptId(Long attemptId) {
        return findRecordsByAttemptId(null, attemptId);
    }

    @Override
    public List<ValidationResultRecord> findRecordsByAttemptId(Handle handle, Long attemptId) {
        return storage.stream()
                .filter(r -> r.attemptId().equals(attemptId))
                .toList();
    }

    @Override
    public List<ValidationResultRecord> findByReasonCode(String reasonCode) {
        return findByReasonCode(null, reasonCode);
    }

    @Override
    public List<ValidationResultRecord> findByReasonCode(Handle handle, String reasonCode) {
        return storage.stream()
                .filter(r -> r.reasonCode().equalsIgnoreCase(reasonCode))
                .toList();
    }

    public void clear() {
        storage.clear();
    }
}
