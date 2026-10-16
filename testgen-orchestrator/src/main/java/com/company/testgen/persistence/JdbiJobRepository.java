package com.company.testgen.persistence;

import com.company.testgen.model.JobRecord;
import com.company.testgen.model.RedactionReport;
import com.company.testgen.util.Json;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Update;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBI 3 implementation of {@link JobRepository} (§6.14, §7.3, §7.4).
 */
public class JdbiJobRepository implements JobRepository {

    private static final String INSERT_SQL = """
            INSERT INTO jobs (
                id, idempotency_key, target_area, test_description, requester,
                recording_branch, state, confidence_tier, failure_reason,
                prompt_bundle_hash, model_id, orchestrator_version, recorder_version,
                base_commit_sha, lease_owner, lease_epoch, lease_expires_at, build_url,
                redaction_report, mr_iid, mr_url, created_at, updated_at, completed_at
            ) VALUES (
                :id, :idempotencyKey, :targetArea, :testDescription, :requester,
                :recordingBranch, :state, :confidenceTier, :failureReason,
                :promptBundleHash, :modelId, :orchestratorVersion, :recorderVersion,
                :baseCommitSha, :leaseOwner, :leaseEpoch, :leaseExpiresAt, :buildUrl,
                CAST(:redactionReport AS jsonb), :mrIid, :mrUrl, :createdAt, :updatedAt, :completedAt
            )
            """;

    private static final String SELECT_BY_ID_SQL = """
            SELECT * FROM jobs WHERE id = :id
            """;

    private static final String SELECT_BY_KEY_SQL = """
            SELECT * FROM jobs WHERE idempotency_key = :idempotencyKey
            """;

    private static final String UPDATE_SQL = """
            UPDATE jobs
            SET target_area = :targetArea,
                test_description = :testDescription,
                requester = :requester,
                recording_branch = :recordingBranch,
                state = :state,
                confidence_tier = :confidenceTier,
                failure_reason = :failureReason,
                prompt_bundle_hash = :promptBundleHash,
                model_id = :modelId,
                orchestrator_version = :orchestratorVersion,
                recorder_version = :recorderVersion,
                base_commit_sha = :baseCommitSha,
                lease_owner = :leaseOwner,
                lease_epoch = :leaseEpoch,
                lease_expires_at = :leaseExpiresAt,
                build_url = :buildUrl,
                redaction_report = CAST(:redactionReport AS jsonb),
                mr_iid = :mrIid,
                mr_url = :mrUrl,
                updated_at = now(),
                completed_at = :completedAt
            WHERE id = :id
            """;

    private static final String DELETE_SQL = """
            DELETE FROM jobs WHERE id = :id
            """;

    private static final String SELECT_ALL_SQL = """
            SELECT * FROM jobs ORDER BY created_at DESC
            """;

    private static final String SELECT_BY_STATE_SQL = """
            SELECT * FROM jobs WHERE state = :state ORDER BY created_at DESC
            """;

    private static final String SELECT_BY_AREA_SQL = """
            SELECT * FROM jobs WHERE target_area = :targetArea ORDER BY created_at DESC
            """;

    private static final String RECLAIM_SQL = """
            UPDATE jobs
               SET lease_owner      = :owner,
                   lease_epoch      = lease_epoch + 1,
                   lease_expires_at = :expiresAt,
                   updated_at       = now()
             WHERE id = :id
               AND (lease_expires_at IS NULL OR lease_expires_at < now() OR state = 'FAILED')
            RETURNING lease_epoch
            """;

    private static final String UPDATE_STATE_FENCED_SQL = """
            UPDATE jobs
               SET state = :state,
                   updated_at = now(),
                   lease_expires_at = :expiresAt
             WHERE id = :id AND lease_epoch = :epoch
            """;

    private static final String TOUCH_SQL = """
            UPDATE jobs
               SET lease_expires_at = :expiresAt,
                   updated_at       = now()
             WHERE id = :id AND lease_epoch = :epoch
            """;

    private static final String COMPLETE_FENCED_SQL = """
            UPDATE jobs
               SET state           = 'DONE',
                   confidence_tier = :confidenceTier,
                   mr_iid          = :mrIid,
                   mr_url          = :mrUrl,
                   completed_at    = now(),
                   updated_at      = now()
             WHERE id = :id AND lease_epoch = :epoch
            """;

    private static final String FAIL_FENCED_SQL = """
            UPDATE jobs
               SET state          = 'FAILED',
                   failure_reason = :failureReason,
                   completed_at   = now(),
                   updated_at     = now()
             WHERE id = :id AND lease_epoch = :epoch
            """;

    private static final String FIND_EXPIRED_NON_TERMINAL_SQL = """
            SELECT * FROM jobs
             WHERE state NOT IN ('DONE', 'FAILED', 'NEEDS_HUMAN_REVIEW')
               AND lease_expires_at IS NOT NULL
               AND lease_expires_at < :cutoff
             ORDER BY created_at DESC
            """;

    private static final String DONE_WITHOUT_CONFIRMED_MR_SQL = """
            SELECT j.id FROM jobs j
             WHERE j.state = 'DONE'
               AND NOT EXISTS (\
                   SELECT 1 FROM external_effects e
                    WHERE e.job_id = j.id
                      AND e.effect_type = 'MERGE_REQUEST'
                      AND e.status = 'CONFIRMED'
               )
            """;

    private static final String MARK_FAILED_SQL = """
            UPDATE jobs
               SET state = 'FAILED',
                   failure_reason = :failureReason,
                   completed_at = now(),
                   updated_at = now()
             WHERE id = :id
            """;

    private static final String RECORD_REDACTION_SQL = """
            UPDATE jobs
               SET redaction_report = CAST(:reportJson AS jsonb),
                   updated_at = now()
             WHERE id = :id
            """;

    private final Jdbi jdbi;

    public JdbiJobRepository(Jdbi jdbi) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi cannot be null");
    }

    @Override
    public void insert(JobRecord job) {
        jdbi.useHandle(h -> insert(h, job));
    }

    @Override
    public void insert(Handle handle, JobRecord job) {
        Objects.requireNonNull(job, "job cannot be null");
        bindJob(handle.createUpdate(INSERT_SQL), job).execute();
    }

    @Override
    public Optional<JobRecord> findById(UUID id) {
        return jdbi.withHandle(h -> findById(h, id));
    }

    @Override
    public Optional<JobRecord> findById(Handle handle, UUID id) {
        return handle.createQuery(SELECT_BY_ID_SQL)
                .bind("id", id)
                .mapTo(JobRecord.class)
                .findOne();
    }

    @Override
    public Optional<JobRecord> findByIdempotencyKey(String idempotencyKey) {
        return jdbi.withHandle(h -> findByIdempotencyKey(h, idempotencyKey));
    }

    @Override
    public Optional<JobRecord> findByIdempotencyKey(Handle handle, String idempotencyKey) {
        return handle.createQuery(SELECT_BY_KEY_SQL)
                .bind("idempotencyKey", idempotencyKey)
                .mapTo(JobRecord.class)
                .findOne();
    }

    @Override
    public boolean update(JobRecord job) {
        return jdbi.withHandle(h -> update(h, job));
    }

    @Override
    public boolean update(Handle handle, JobRecord job) {
        Objects.requireNonNull(job, "job cannot be null");
        return bindJob(handle.createUpdate(UPDATE_SQL), job).execute() > 0;
    }

    @Override
    public boolean deleteById(UUID id) {
        return jdbi.withHandle(h -> deleteById(h, id));
    }

    @Override
    public boolean deleteById(Handle handle, UUID id) {
        return handle.createUpdate(DELETE_SQL)
                .bind("id", id)
                .execute() > 0;
    }

    @Override
    public List<JobRecord> findAll() {
        return jdbi.withHandle(this::findAll);
    }

    @Override
    public List<JobRecord> findAll(Handle handle) {
        return handle.createQuery(SELECT_ALL_SQL)
                .mapTo(JobRecord.class)
                .list();
    }

    @Override
    public List<JobRecord> findByState(String state) {
        return jdbi.withHandle(h -> findByState(h, state));
    }

    @Override
    public List<JobRecord> findByState(Handle handle, String state) {
        return handle.createQuery(SELECT_BY_STATE_SQL)
                .bind("state", state)
                .mapTo(JobRecord.class)
                .list();
    }

    @Override
    public List<JobRecord> findByTargetArea(String targetArea) {
        return jdbi.withHandle(h -> findByTargetArea(h, targetArea));
    }

    @Override
    public List<JobRecord> findByTargetArea(Handle handle, String targetArea) {
        return handle.createQuery(SELECT_BY_AREA_SQL)
                .bind("targetArea", targetArea)
                .mapTo(JobRecord.class)
                .list();
    }

    @Override
    public Optional<Long> reclaim(UUID id, String owner, Instant expiresAt) {
        return jdbi.withHandle(h -> reclaim(h, id, owner, expiresAt));
    }

    @Override
    public Optional<Long> reclaim(Handle handle, UUID id, String owner, Instant expiresAt) {
        return handle.createQuery(RECLAIM_SQL)
                .bind("id", id)
                .bind("owner", owner)
                .bind("expiresAt", expiresAt != null ? Timestamp.from(expiresAt) : null)
                .mapTo(Long.class)
                .findOne();
    }

    @Override
    public boolean updateStateFenced(UUID id, String state, long leaseEpoch, Instant expiresAt) {
        return jdbi.withHandle(h -> updateStateFenced(h, id, state, leaseEpoch, expiresAt));
    }

    @Override
    public boolean updateStateFenced(Handle handle, UUID id, String state, long leaseEpoch, Instant expiresAt) {
        return handle.createUpdate(UPDATE_STATE_FENCED_SQL)
                .bind("id", id)
                .bind("state", state)
                .bind("epoch", leaseEpoch)
                .bind("expiresAt", expiresAt != null ? Timestamp.from(expiresAt) : null)
                .execute() > 0;
    }

    @Override
    public boolean touch(UUID id, long leaseEpoch, Instant expiresAt) {
        return jdbi.withHandle(h -> touch(h, id, leaseEpoch, expiresAt));
    }

    @Override
    public boolean touch(Handle handle, UUID id, long leaseEpoch, Instant expiresAt) {
        return handle.createUpdate(TOUCH_SQL)
                .bind("id", id)
                .bind("epoch", leaseEpoch)
                .bind("expiresAt", expiresAt != null ? Timestamp.from(expiresAt) : null)
                .execute() > 0;
    }

    @Override
    public boolean completeFenced(UUID id, long leaseEpoch, String confidenceTier, Integer mrIid, String mrUrl) {
        return jdbi.withHandle(h -> completeFenced(h, id, leaseEpoch, confidenceTier, mrIid, mrUrl));
    }

    @Override
    public boolean completeFenced(Handle handle, UUID id, long leaseEpoch, String confidenceTier, Integer mrIid, String mrUrl) {
        return handle.createUpdate(COMPLETE_FENCED_SQL)
                .bind("id", id)
                .bind("epoch", leaseEpoch)
                .bind("confidenceTier", confidenceTier)
                .bind("mrIid", mrIid)
                .bind("mrUrl", mrUrl)
                .execute() > 0;
    }

    @Override
    public boolean failFenced(UUID id, long leaseEpoch, String failureReason) {
        return jdbi.withHandle(h -> failFenced(h, id, leaseEpoch, failureReason));
    }

    @Override
    public boolean failFenced(Handle handle, UUID id, long leaseEpoch, String failureReason) {
        return handle.createUpdate(FAIL_FENCED_SQL)
                .bind("id", id)
                .bind("epoch", leaseEpoch)
                .bind("failureReason", failureReason)
                .execute() > 0;
    }

    @Override
    public List<JobRecord> findExpiredNonTerminal(Duration age) {
        return jdbi.withHandle(h -> findExpiredNonTerminal(h, age));
    }

    @Override
    public List<JobRecord> findExpiredNonTerminal(Handle handle, Duration age) {
        Instant cutoff = Instant.now().minus(age);
        return handle.createQuery(FIND_EXPIRED_NON_TERMINAL_SQL)
                .bind("cutoff", Timestamp.from(cutoff))
                .mapTo(JobRecord.class)
                .list();
    }

    @Override
    public List<UUID> doneWithoutConfirmedMr() {
        return jdbi.withHandle(this::doneWithoutConfirmedMr);
    }

    @Override
    public List<UUID> doneWithoutConfirmedMr(Handle handle) {
        return handle.createQuery(DONE_WITHOUT_CONFIRMED_MR_SQL)
                .mapTo(UUID.class)
                .list();
    }

    @Override
    public void markFailed(UUID id, String failureReason) {
        jdbi.useHandle(h -> markFailed(h, id, failureReason));
    }

    @Override
    public void markFailed(Handle handle, UUID id, String failureReason) {
        handle.createUpdate(MARK_FAILED_SQL)
                .bind("id", id)
                .bind("failureReason", failureReason)
                .execute();
    }

    @Override
    public void recordRedaction(UUID id, RedactionReport report) {
        Objects.requireNonNull(id, "id must not be null");
        String reportJson = report != null ? Json.stringify(report) : null;
        jdbi.useHandle(h -> recordRedaction(h, id, reportJson));
    }

    @Override
    public void recordRedaction(Handle handle, UUID id, String reportJson) {
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(id, "id must not be null");
        handle.createUpdate(RECORD_REDACTION_SQL)
                .bind("id", id)
                .bind("reportJson", reportJson)
                .execute();
    }

    private Update bindJob(Update update, JobRecord job) {
        return update
                .bind("id", job.id())
                .bind("idempotencyKey", job.idempotencyKey())
                .bind("targetArea", job.targetArea())
                .bind("testDescription", job.testDescription())
                .bind("requester", job.requester())
                .bind("recordingBranch", job.recordingBranch())
                .bind("state", job.state())
                .bind("confidenceTier", job.confidenceTier())
                .bind("failureReason", job.failureReason())
                .bind("promptBundleHash", job.promptBundleHash())
                .bind("modelId", job.modelId())
                .bind("orchestratorVersion", job.orchestratorVersion())
                .bind("recorderVersion", job.recorderVersion())
                .bind("baseCommitSha", job.baseCommitSha())
                .bind("leaseOwner", job.leaseOwner())
                .bind("leaseEpoch", job.leaseEpoch())
                .bind("leaseExpiresAt", job.leaseExpiresAt() != null ? Timestamp.from(job.leaseExpiresAt()) : null)
                .bind("buildUrl", job.buildUrl())
                .bind("redactionReport", job.redactionReport())
                .bind("mrIid", job.mrIid())
                .bind("mrUrl", job.mrUrl())
                .bind("createdAt", job.createdAt() != null ? Timestamp.from(job.createdAt()) : null)
                .bind("updatedAt", job.updatedAt() != null ? Timestamp.from(job.updatedAt()) : null)
                .bind("completedAt", job.completedAt() != null ? Timestamp.from(job.completedAt()) : null);
    }
}
