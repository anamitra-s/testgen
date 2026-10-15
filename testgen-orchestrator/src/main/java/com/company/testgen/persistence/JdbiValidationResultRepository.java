package com.company.testgen.persistence;

import com.company.testgen.model.Severity;
import com.company.testgen.model.ValidationResult;
import com.company.testgen.model.ValidationViolation;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * JDBI 3 implementation of {@link ValidationResultRepository} (§6.13, §6.14).
 */
public class JdbiValidationResultRepository implements ValidationResultRepository {

    private static final String INSERT_SQL = """
            INSERT INTO validation_results (
                attempt_id, layer, element_ref_id, locator_expression,
                reason_code, match_count, severity, detail
            ) VALUES (
                :attemptId, :layer, :elementRefId, :locatorExpression,
                :reasonCode, :matchCount, :severity, :detail
            )
            """;

    private static final String SELECT_BY_ATTEMPT_SQL = """
            SELECT * FROM validation_results WHERE attempt_id = :attemptId ORDER BY id ASC
            """;

    private static final String SELECT_BY_REASON_SQL = """
            SELECT * FROM validation_results WHERE reason_code = :reasonCode ORDER BY id ASC
            """;

    private final Jdbi jdbi;

    public JdbiValidationResultRepository(Jdbi jdbi) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi cannot be null");
    }

    public JdbiValidationResultRepository(Db db) {
        this(Objects.requireNonNull(db, "db cannot be null").jdbi());
    }

    @Override
    public void saveAll(Long attemptId, ValidationResult validation) {
        saveAll(attemptId, "L0_L1", validation);
    }

    @Override
    public void saveAll(Long attemptId, String layer, ValidationResult validation) {
        if (validation == null || validation.violations().isEmpty()) {
            return;
        }
        jdbi.useHandle(handle -> saveAll(handle, attemptId, layer, validation));
    }

    @Override
    public void saveAll(Handle handle, Long attemptId, String layer, ValidationResult validation) {
        if (validation == null || validation.violations().isEmpty()) {
            return;
        }
        PreparedBatch batch = handle.prepareBatch(INSERT_SQL);
        for (ValidationViolation v : validation.violations()) {
            batch.bind("attemptId", attemptId)
                    .bind("layer", layer != null ? layer : "L0_L1")
                    .bind("elementRefId", v.elementRefId())
                    .bind("locatorExpression", v.locatorExpression())
                    .bind("reasonCode", v.reasonCode())
                    .bind("matchCount", v.matchCount())
                    .bind("severity", v.severity() != null ? v.severity().name() : Severity.BLOCKING.name())
                    .bind("detail", v.detail())
                    .add();
        }
        batch.execute();
    }

    @Override
    public void save(Long attemptId, String layer, ValidationViolation violation) {
        jdbi.useHandle(handle -> save(handle, attemptId, layer, violation));
    }

    @Override
    public void save(Handle handle, Long attemptId, String layer, ValidationViolation violation) {
        handle.createUpdate(INSERT_SQL)
                .bind("attemptId", attemptId)
                .bind("layer", layer != null ? layer : "L0_L1")
                .bind("elementRefId", violation.elementRefId())
                .bind("locatorExpression", violation.locatorExpression())
                .bind("reasonCode", violation.reasonCode())
                .bind("matchCount", violation.matchCount())
                .bind("severity", violation.severity() != null ? violation.severity().name() : Severity.BLOCKING.name())
                .bind("detail", violation.detail())
                .execute();
    }

    @Override
    public List<ValidationViolation> findByAttemptId(Long attemptId) {
        return findRecordsByAttemptId(attemptId).stream()
                .map(ValidationResultRecord::toViolation)
                .toList();
    }

    @Override
    public List<ValidationViolation> findByAttemptId(Handle handle, Long attemptId) {
        return findRecordsByAttemptId(handle, attemptId).stream()
                .map(ValidationResultRecord::toViolation)
                .toList();
    }

    @Override
    public List<ValidationResultRecord> findRecordsByAttemptId(Long attemptId) {
        return jdbi.withHandle(handle -> findRecordsByAttemptId(handle, attemptId));
    }

    @Override
    public List<ValidationResultRecord> findRecordsByAttemptId(Handle handle, Long attemptId) {
        return handle.createQuery(SELECT_BY_ATTEMPT_SQL)
                .bind("attemptId", attemptId)
                .map(this::mapRow)
                .list();
    }

    @Override
    public List<ValidationResultRecord> findByReasonCode(String reasonCode) {
        return jdbi.withHandle(handle -> findByReasonCode(handle, reasonCode));
    }

    @Override
    public List<ValidationResultRecord> findByReasonCode(Handle handle, String reasonCode) {
        return handle.createQuery(SELECT_BY_REASON_SQL)
                .bind("reasonCode", reasonCode)
                .map(this::mapRow)
                .list();
    }

    private ValidationResultRecord mapRow(ResultSet rs, StatementContext ctx) throws SQLException {
        String severityStr = rs.getString("severity");
        Severity severity = Severity.BLOCKING;
        if (severityStr != null) {
            try {
                severity = Severity.valueOf(severityStr.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }
        Integer matchCount = rs.getObject("match_count") != null ? rs.getInt("match_count") : null;

        return new ValidationResultRecord(
                rs.getLong("id"),
                rs.getLong("attempt_id"),
                rs.getString("layer"),
                rs.getString("element_ref_id"),
                rs.getString("locator_expression"),
                rs.getString("reason_code"),
                matchCount,
                severity,
                rs.getString("detail")
        );
    }
}
