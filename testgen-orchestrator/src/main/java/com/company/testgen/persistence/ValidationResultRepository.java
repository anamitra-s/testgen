package com.company.testgen.persistence;

import com.company.testgen.model.ValidationResult;
import com.company.testgen.model.ValidationViolation;
import org.jdbi.v3.core.Handle;

import java.util.List;

/**
 * Repository interface for managing validation violations in {@code validation_results} (§6.13, §6.14).
 */
public interface ValidationResultRepository {

    /**
     * Persists all violations in a ValidationResult for the given attempt (§6.13).
     */
    void saveAll(Long attemptId, ValidationResult validation);

    void saveAll(Long attemptId, String layer, ValidationResult validation);

    void saveAll(Handle handle, Long attemptId, String layer, ValidationResult validation);

    void save(Long attemptId, String layer, ValidationViolation violation);

    void save(Handle handle, Long attemptId, String layer, ValidationViolation violation);

    List<ValidationViolation> findByAttemptId(Long attemptId);

    List<ValidationViolation> findByAttemptId(Handle handle, Long attemptId);

    List<ValidationResultRecord> findRecordsByAttemptId(Long attemptId);

    List<ValidationResultRecord> findRecordsByAttemptId(Handle handle, Long attemptId);

    List<ValidationResultRecord> findByReasonCode(String reasonCode);

    List<ValidationResultRecord> findByReasonCode(Handle handle, String reasonCode);
}
