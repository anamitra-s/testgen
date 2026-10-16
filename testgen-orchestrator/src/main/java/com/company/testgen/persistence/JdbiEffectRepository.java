package com.company.testgen.persistence;

import com.company.testgen.idempotency.EffectType;
import com.company.testgen.idempotency.LeaseLostException;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBI 3 implementation of {@link EffectRepository} (§6.14, §7.4, §7.5).
 * All worker effect mutations are strictly fenced against {@code jobs.lease_epoch} (ADR-012, §7.4).
 */
public class JdbiEffectRepository implements EffectRepository {

    private static final String SELECT_BY_NATURAL_KEY_SQL = """
            SELECT * FROM external_effects
            WHERE job_id = :jobId AND effect_type = :effectType AND effect_key = :effectKey
            """;

    private static final String RESERVE_SQL = """
            INSERT INTO external_effects (
                job_id, effect_type, effect_key, status, lease_epoch, attempts, created_at
            ) VALUES (
                :jobId, :effectType, :effectKey, 'PENDING', :leaseEpoch, 0, now()
            )
            ON CONFLICT (job_id, effect_type, effect_key) DO NOTHING
            """;

    private static final String CONFIRM_SQL = """
            UPDATE external_effects SET
                status = 'CONFIRMED',
                external_id = :externalId,
                confirmed_at = now()
            WHERE job_id = :jobId AND effect_type = :effectType AND effect_key = :effectKey
            """;

    private static final String INCREMENT_ATTEMPTS_SQL = """
            UPDATE external_effects SET
                attempts = attempts + 1
            WHERE job_id = :jobId AND effect_type = :effectType AND effect_key = :effectKey
            """;

    private static final String ABANDON_SQL = """
            UPDATE external_effects SET
                status = 'ABANDONED'
            WHERE job_id = :jobId AND effect_type = :effectType AND effect_key = :effectKey
            """;

    private static final String SELECT_PENDING_OLDER_THAN_SQL = """
            SELECT * FROM external_effects
            WHERE status = 'PENDING' AND created_at < :cutoff
            ORDER BY created_at ASC
            """;

    private static final String SELECT_BY_JOB_SQL = """
            SELECT * FROM external_effects
            WHERE job_id = :jobId
            ORDER BY created_at ASC
            """;

    private final Jdbi jdbi;

    public JdbiEffectRepository(Jdbi jdbi) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi cannot be null");
    }

    private void checkLeaseEpoch(Handle handle, UUID jobId, long leaseEpoch) {
        if (jobId == null) return;
        Optional<Long> currentEpoch = handle.createQuery("SELECT lease_epoch FROM jobs WHERE id = :jobId")
                .bind("jobId", jobId)
                .mapTo(Long.class)
                .findOne();
        if (currentEpoch.isPresent() && currentEpoch.get() != leaseEpoch) {
            throw new LeaseLostException(jobId, leaseEpoch);
        }
    }

    @Override
    public Optional<EffectRecord> find(UUID jobId, EffectType type, String effectKey) {
        return jdbi.withHandle(handle -> find(handle, jobId, type, effectKey));
    }

    @Override
    public Optional<EffectRecord> find(Handle handle, UUID jobId, EffectType type, String effectKey) {
        return handle.createQuery(SELECT_BY_NATURAL_KEY_SQL)
                .bind("jobId", jobId)
                .bind("effectType", type.name())
                .bind("effectKey", effectKey)
                .map(this::mapRow)
                .findOne();
    }

    @Override
    public void reserve(UUID jobId, EffectType type, String effectKey, long leaseEpoch) {
        jdbi.useHandle(handle -> reserve(handle, jobId, type, effectKey, leaseEpoch));
    }

    @Override
    public void reserve(Handle handle, UUID jobId, EffectType type, String effectKey, long leaseEpoch) {
        checkLeaseEpoch(handle, jobId, leaseEpoch);
        handle.createUpdate(RESERVE_SQL)
                .bind("jobId", jobId)
                .bind("effectType", type.name())
                .bind("effectKey", effectKey)
                .bind("leaseEpoch", leaseEpoch)
                .execute();
    }

    @Override
    public void confirm(UUID jobId, long leaseEpoch, EffectType type, String effectKey, String externalId) {
        jdbi.useHandle(handle -> confirm(handle, jobId, leaseEpoch, type, effectKey, externalId));
    }

    @Override
    public void confirm(Handle handle, UUID jobId, long leaseEpoch, EffectType type, String effectKey, String externalId) {
        checkLeaseEpoch(handle, jobId, leaseEpoch);
        handle.createUpdate(CONFIRM_SQL)
                .bind("jobId", jobId)
                .bind("effectType", type.name())
                .bind("effectKey", effectKey)
                .bind("externalId", externalId)
                .execute();
    }

    @Override
    public void confirm(UUID jobId, EffectType type, String effectKey, String externalId) {
        jdbi.useHandle(handle -> confirm(handle, jobId, type, effectKey, externalId));
    }

    @Override
    public void confirm(Handle handle, UUID jobId, EffectType type, String effectKey, String externalId) {
        Optional<EffectRecord> existing = find(handle, jobId, type, effectKey);
        if (existing.isPresent()) {
            checkLeaseEpoch(handle, jobId, existing.get().leaseEpoch());
        }
        handle.createUpdate(CONFIRM_SQL)
                .bind("jobId", jobId)
                .bind("effectType", type.name())
                .bind("effectKey", effectKey)
                .bind("externalId", externalId)
                .execute();
    }

    @Override
    public void confirm(EffectRecord record, String externalId) {
        Objects.requireNonNull(record, "record cannot be null");
        confirmUnfenced(record.jobId(), record.effectType(), record.effectKey(), externalId);
    }

    @Override
    public void confirm(Handle handle, EffectRecord record, String externalId) {
        Objects.requireNonNull(record, "record cannot be null");
        confirmUnfenced(handle, record.jobId(), record.effectType(), record.effectKey(), externalId);
    }

    private void confirmUnfenced(UUID jobId, EffectType type, String effectKey, String externalId) {
        jdbi.useHandle(handle -> confirmUnfenced(handle, jobId, type, effectKey, externalId));
    }

    private void confirmUnfenced(Handle handle, UUID jobId, EffectType type, String effectKey, String externalId) {
        handle.createUpdate(CONFIRM_SQL)
                .bind("jobId", jobId)
                .bind("effectType", type.name())
                .bind("effectKey", effectKey)
                .bind("externalId", externalId)
                .execute();
    }

    @Override
    public void incrementAttempts(UUID jobId, long leaseEpoch, EffectType type, String effectKey) {
        jdbi.useHandle(handle -> incrementAttempts(handle, jobId, leaseEpoch, type, effectKey));
    }

    @Override
    public void incrementAttempts(Handle handle, UUID jobId, long leaseEpoch, EffectType type, String effectKey) {
        checkLeaseEpoch(handle, jobId, leaseEpoch);
        handle.createUpdate(INCREMENT_ATTEMPTS_SQL)
                .bind("jobId", jobId)
                .bind("effectType", type.name())
                .bind("effectKey", effectKey)
                .execute();
    }

    @Override
    public void incrementAttempts(UUID jobId, EffectType type, String effectKey) {
        jdbi.useHandle(handle -> incrementAttempts(handle, jobId, type, effectKey));
    }

    @Override
    public void incrementAttempts(Handle handle, UUID jobId, EffectType type, String effectKey) {
        Optional<EffectRecord> existing = find(handle, jobId, type, effectKey);
        if (existing.isPresent()) {
            checkLeaseEpoch(handle, jobId, existing.get().leaseEpoch());
        }
        handle.createUpdate(INCREMENT_ATTEMPTS_SQL)
                .bind("jobId", jobId)
                .bind("effectType", type.name())
                .bind("effectKey", effectKey)
                .execute();
    }

    @Override
    public void abandon(UUID jobId, long leaseEpoch, EffectType type, String effectKey, String reason) {
        jdbi.useHandle(handle -> abandon(handle, jobId, leaseEpoch, type, effectKey, reason));
    }

    @Override
    public void abandon(Handle handle, UUID jobId, long leaseEpoch, EffectType type, String effectKey, String reason) {
        checkLeaseEpoch(handle, jobId, leaseEpoch);
        handle.createUpdate(ABANDON_SQL)
                .bind("jobId", jobId)
                .bind("effectType", type.name())
                .bind("effectKey", effectKey)
                .execute();
    }

    @Override
    public void abandon(UUID jobId, EffectType type, String effectKey, String reason) {
        jdbi.useHandle(handle -> abandon(handle, jobId, type, effectKey, reason));
    }

    @Override
    public void abandon(Handle handle, UUID jobId, EffectType type, String effectKey, String reason) {
        Optional<EffectRecord> existing = find(handle, jobId, type, effectKey);
        if (existing.isPresent()) {
            checkLeaseEpoch(handle, jobId, existing.get().leaseEpoch());
        }
        handle.createUpdate(ABANDON_SQL)
                .bind("jobId", jobId)
                .bind("effectType", type.name())
                .bind("effectKey", effectKey)
                .execute();
    }

    @Override
    public void abandon(EffectRecord record, String reason) {
        Objects.requireNonNull(record, "record cannot be null");
        abandonUnfenced(record.jobId(), record.effectType(), record.effectKey(), reason);
    }

    @Override
    public void abandon(Handle handle, EffectRecord record, String reason) {
        Objects.requireNonNull(record, "record cannot be null");
        abandonUnfenced(handle, record.jobId(), record.effectType(), record.effectKey(), reason);
    }

    private void abandonUnfenced(UUID jobId, EffectType type, String effectKey, String reason) {
        jdbi.useHandle(handle -> abandonUnfenced(handle, jobId, type, effectKey, reason));
    }

    private void abandonUnfenced(Handle handle, UUID jobId, EffectType type, String effectKey, String reason) {
        handle.createUpdate(ABANDON_SQL)
                .bind("jobId", jobId)
                .bind("effectType", type.name())
                .bind("effectKey", effectKey)
                .execute();
    }

    @Override
    public List<EffectRecord> findPendingOlderThan(Duration age) {
        return jdbi.withHandle(handle -> findPendingOlderThan(handle, age));
    }

    @Override
    public List<EffectRecord> findPendingOlderThan(Handle handle, Duration age) {
        Instant cutoff = Instant.now().minus(age);
        return handle.createQuery(SELECT_PENDING_OLDER_THAN_SQL)
                .bind("cutoff", Timestamp.from(cutoff))
                .map(this::mapRow)
                .list();
    }

    @Override
    public List<EffectRecord> findByJobId(UUID jobId) {
        return jdbi.withHandle(handle -> findByJobId(handle, jobId));
    }

    @Override
    public List<EffectRecord> findByJobId(Handle handle, UUID jobId) {
        return handle.createQuery(SELECT_BY_JOB_SQL)
                .bind("jobId", jobId)
                .map(this::mapRow)
                .list();
    }

    private EffectRecord mapRow(ResultSet rs, StatementContext ctx) throws SQLException {
        Timestamp createdAtTs = rs.getTimestamp("created_at");
        Timestamp confirmedAtTs = rs.getTimestamp("confirmed_at");

        EffectType effectType = EffectType.valueOf(rs.getString("effect_type").toUpperCase());
        EffectStatus status = EffectStatus.valueOf(rs.getString("status").toUpperCase());

        return new EffectRecord(
                rs.getLong("id"),
                rs.getObject("job_id", UUID.class),
                effectType,
                rs.getString("effect_key"),
                status,
                rs.getString("external_id"),
                rs.getLong("lease_epoch"),
                rs.getInt("attempts"),
                createdAtTs != null ? createdAtTs.toInstant() : null,
                confirmedAtTs != null ? confirmedAtTs.toInstant() : null
        );
    }
}
