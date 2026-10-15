package com.company.testgen.persistence;

import com.company.testgen.model.VerificationResult;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBI 3 implementation of {@link VerificationRepository} (§6.8, §6.13, §6.14).
 */
public class JdbiVerificationRepository implements VerificationRepository {

    private static final String INSERT_SQL = """
            INSERT INTO verification_runs (
                job_id, outcome, skip_reason, duration_ms, failure_tail, blocking, created_at
            ) VALUES (
                :jobId, :outcome, :skipReason, :durationMs, :failureTail, :blocking, now()
            )
            """;

    private static final String SELECT_LATEST_BY_JOB_SQL = """
            SELECT * FROM verification_runs WHERE job_id = :jobId ORDER BY created_at DESC, id DESC LIMIT 1
            """;

    private static final String SELECT_BY_JOB_SQL = """
            SELECT * FROM verification_runs WHERE job_id = :jobId ORDER BY created_at ASC, id ASC
            """;

    private static final String SELECT_ALL_SQL = """
            SELECT * FROM verification_runs ORDER BY created_at DESC, id DESC
            """;

    private final Jdbi jdbi;

    public JdbiVerificationRepository(Jdbi jdbi) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi cannot be null");
    }

    public JdbiVerificationRepository(Db db) {
        this(Objects.requireNonNull(db, "db cannot be null").jdbi());
    }

    @Override
    public void save(UUID jobId, VerificationResult result) {
        jdbi.useHandle(handle -> save(handle, jobId, result));
    }

    @Override
    public void save(Handle handle, UUID jobId, VerificationResult result) {
        if (result == null || jobId == null) {
            return;
        }
        handle.createUpdate(INSERT_SQL)
                .bind("jobId", jobId)
                .bind("outcome", result.outcome())
                .bind("skipReason", result.skipReason())
                .bind("durationMs", result.durationMs())
                .bind("failureTail", result.failureTail())
                .bind("blocking", result.blocking())
                .execute();
    }

    @Override
    public Optional<VerificationRecord> findLatestByJobId(UUID jobId) {
        return jdbi.withHandle(handle -> findLatestByJobId(handle, jobId));
    }

    @Override
    public Optional<VerificationRecord> findLatestByJobId(Handle handle, UUID jobId) {
        return handle.createQuery(SELECT_LATEST_BY_JOB_SQL)
                .bind("jobId", jobId)
                .map(this::mapRow)
                .findOne();
    }

    @Override
    public List<VerificationRecord> findByJobId(UUID jobId) {
        return jdbi.withHandle(handle -> findByJobId(handle, jobId));
    }

    @Override
    public List<VerificationRecord> findByJobId(Handle handle, UUID jobId) {
        return handle.createQuery(SELECT_BY_JOB_SQL)
                .bind("jobId", jobId)
                .map(this::mapRow)
                .list();
    }

    @Override
    public List<VerificationRecord> findAll() {
        return jdbi.withHandle(this::findAll);
    }

    @Override
    public List<VerificationRecord> findAll(Handle handle) {
        return handle.createQuery(SELECT_ALL_SQL)
                .map(this::mapRow)
                .list();
    }

    private VerificationRecord mapRow(ResultSet rs, StatementContext ctx) throws SQLException {
        Timestamp createdAtTs = rs.getTimestamp("created_at");
        Integer durationMs = rs.getObject("duration_ms") != null ? rs.getInt("duration_ms") : null;

        return new VerificationRecord(
                rs.getLong("id"),
                rs.getObject("job_id", UUID.class),
                rs.getString("outcome"),
                rs.getString("skip_reason"),
                durationMs,
                rs.getString("failure_tail"),
                rs.getBoolean("blocking"),
                createdAtTs != null ? createdAtTs.toInstant() : null
        );
    }
}
