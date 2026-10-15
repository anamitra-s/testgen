package com.company.testgen.persistence;

import com.company.testgen.model.Phase;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBI 3 implementation of {@link RetryAttemptRepository} (§6.13, §6.14).
 */
public class JdbiRetryAttemptRepository implements RetryAttemptRepository {

    private static final String INSERT_SQL = """
            INSERT INTO retry_attempts (
                job_id, attempt_no, phase, started_at
            ) VALUES (
                :jobId, :attemptNo, :phase, now()
            ) RETURNING id
            """;

    private static final String FINISH_SQL = """
            UPDATE retry_attempts SET
                passed = :passed,
                feedback_sent = :feedbackSent,
                finished_at = now()
            WHERE id = :attemptId
            """;

    private static final String SELECT_BY_ID_SQL = """
            SELECT * FROM retry_attempts WHERE id = :id
            """;

    private static final String SELECT_BY_JOB_ATTEMPT_PHASE_SQL = """
            SELECT * FROM retry_attempts
            WHERE job_id = :jobId AND attempt_no = :attemptNo AND phase = :phase
            """;

    private static final String SELECT_BY_JOB_SQL = """
            SELECT * FROM retry_attempts WHERE job_id = :jobId ORDER BY attempt_no ASC, started_at ASC
            """;

    private final Jdbi jdbi;

    public JdbiRetryAttemptRepository(Jdbi jdbi) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi cannot be null");
    }

    public JdbiRetryAttemptRepository(Db db) {
        this(Objects.requireNonNull(db, "db cannot be null").jdbi());
    }

    @Override
    public Long begin(UUID jobId, int attemptNo, Phase phase) {
        return begin(jobId, attemptNo, phase != null ? phase.name() : null);
    }

    @Override
    public Long begin(UUID jobId, int attemptNo, String phase) {
        return jdbi.withHandle(handle -> begin(handle, jobId, attemptNo, phase));
    }

    @Override
    public Long begin(Handle handle, UUID jobId, int attemptNo, String phase) {
        return handle.createQuery(INSERT_SQL)
                .bind("jobId", jobId)
                .bind("attemptNo", attemptNo)
                .bind("phase", phase)
                .mapTo(Long.class)
                .one();
    }

    @Override
    public void finish(Long attemptId, boolean passed) {
        finish(attemptId, passed, null);
    }

    @Override
    public void finish(Long attemptId, boolean passed, String feedbackSent) {
        jdbi.useHandle(handle -> finish(handle, attemptId, passed, feedbackSent));
    }

    @Override
    public void finish(Handle handle, Long attemptId, boolean passed, String feedbackSent) {
        handle.createUpdate(FINISH_SQL)
                .bind("attemptId", attemptId)
                .bind("passed", passed)
                .bind("feedbackSent", feedbackSent)
                .execute();
    }

    @Override
    public Optional<RetryAttemptRecord> findById(Long id) {
        return jdbi.withHandle(handle -> findById(handle, id));
    }

    @Override
    public Optional<RetryAttemptRecord> findById(Handle handle, Long id) {
        return handle.createQuery(SELECT_BY_ID_SQL)
                .bind("id", id)
                .map(this::mapRow)
                .findOne();
    }

    @Override
    public Optional<RetryAttemptRecord> find(UUID jobId, int attemptNo, String phase) {
        return jdbi.withHandle(handle -> find(handle, jobId, attemptNo, phase));
    }

    @Override
    public Optional<RetryAttemptRecord> find(Handle handle, UUID jobId, int attemptNo, String phase) {
        return handle.createQuery(SELECT_BY_JOB_ATTEMPT_PHASE_SQL)
                .bind("jobId", jobId)
                .bind("attemptNo", attemptNo)
                .bind("phase", phase)
                .map(this::mapRow)
                .findOne();
    }

    @Override
    public List<RetryAttemptRecord> findByJobId(UUID jobId) {
        return jdbi.withHandle(handle -> findByJobId(handle, jobId));
    }

    @Override
    public List<RetryAttemptRecord> findByJobId(Handle handle, UUID jobId) {
        return handle.createQuery(SELECT_BY_JOB_SQL)
                .bind("jobId", jobId)
                .map(this::mapRow)
                .list();
    }

    private RetryAttemptRecord mapRow(ResultSet rs, StatementContext ctx) throws SQLException {
        Timestamp startedAtTs = rs.getTimestamp("started_at");
        Timestamp finishedAtTs = rs.getTimestamp("finished_at");
        Boolean passed = rs.getObject("passed") != null ? rs.getBoolean("passed") : null;

        return new RetryAttemptRecord(
                rs.getLong("id"),
                rs.getObject("job_id", UUID.class),
                rs.getInt("attempt_no"),
                rs.getString("phase"),
                passed,
                rs.getString("feedback_sent"),
                startedAtTs != null ? startedAtTs.toInstant() : null,
                finishedAtTs != null ? finishedAtTs.toInstant() : null
        );
    }
}
