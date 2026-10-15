package com.company.testgen.persistence;

import com.company.testgen.model.JobRecord;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DbBootstrapAndMigrationTest {

    @Test
    void testV1InitSqlExistsAndContainsAllSection614TablesAndIndexes() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/db/migration/V1__init.sql")) {
            assertThat(is).isNotNull();
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            // Table 1: jobs
            assertThat(sql).contains("CREATE TABLE jobs");
            assertThat(sql).contains("idempotency_key       TEXT        NOT NULL UNIQUE");
            assertThat(sql).contains("lease_epoch           BIGINT      NOT NULL DEFAULT 0");
            assertThat(sql).contains("idx_jobs_open_review");
            assertThat(sql).contains("idx_jobs_area_time");
            assertThat(sql).contains("idx_jobs_lease");

            // Table 2: retry_attempts
            assertThat(sql).contains("CREATE TABLE retry_attempts");
            assertThat(sql).contains("UNIQUE (job_id, attempt_no, phase)");

            // Table 3: validation_results
            assertThat(sql).contains("CREATE TABLE validation_results");
            assertThat(sql).contains("idx_vr_attempt");
            assertThat(sql).contains("idx_vr_reason");

            // Table 4: cost_events
            assertThat(sql).contains("CREATE TABLE cost_events");
            assertThat(sql).contains("cache_read_tokens     INT  NOT NULL DEFAULT 0");
            assertThat(sql).contains("idx_cost_job");

            // Table 5: verification_runs
            assertThat(sql).contains("CREATE TABLE verification_runs");
            assertThat(sql).contains("outcome      TEXT NOT NULL");
            assertThat(sql).contains("skip_reason  TEXT");
            assertThat(sql).contains("duration_ms  INT");
            assertThat(sql).contains("failure_tail TEXT");
            assertThat(sql).contains("blocking     BOOLEAN NOT NULL DEFAULT FALSE");

            // Table 6: external_effects
            assertThat(sql).contains("CREATE TABLE external_effects");
            assertThat(sql).contains("effect_type   TEXT NOT NULL");
            assertThat(sql).contains("effect_key    TEXT NOT NULL");
            assertThat(sql).contains("status        TEXT NOT NULL");
            assertThat(sql).contains("UNIQUE (job_id, effect_type, effect_key)");
            assertThat(sql).contains("CREATE INDEX idx_effects_pending ON external_effects (status, created_at)");
            assertThat(sql).contains("WHERE status = 'PENDING'");

            // Table 7: eval_runs
            assertThat(sql).contains("CREATE TABLE eval_runs");
            assertThat(sql).contains("is_baseline        BOOLEAN NOT NULL DEFAULT FALSE");
            assertThat(sql).contains("metrics            JSONB NOT NULL");
            assertThat(sql).contains("CREATE INDEX idx_eval_baseline ON eval_runs (suite, model_id, prompt_bundle_hash)");
            assertThat(sql).contains("WHERE is_baseline");

            // Table 8: eval_case_results
            assertThat(sql).contains("CREATE TABLE eval_case_results");
            assertThat(sql).contains("first_attempt_pass       BOOLEAN NOT NULL");
            assertThat(sql).contains("failure_codes            TEXT[]");
            assertThat(sql).contains("UNIQUE (run_id, case_id, repeat_no)");
            assertThat(sql).contains("CREATE INDEX idx_ecr_run_case ON eval_case_results (run_id, case_id)");
        }
    }

    @Test
    void testJobRecordCreationAndProperties() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(1800);

        JobRecord job = JobRecord.newClaimed(
                id,
                "v2:idemp-key-123",
                "dashboard",
                "Test description",
                "ci-bot",
                "feature/recording",
                "bundle-hash-456",
                "claude-3-5-sonnet",
                "1.0.0",
                "1.0.0",
                "abcdef123",
                "worker-1",
                1L,
                expires
        );

        assertThat(job.id()).isEqualTo(id);
        assertThat(job.idempotencyKey()).isEqualTo("v2:idemp-key-123");
        assertThat(job.state()).isEqualTo("CLAIMED");
        assertThat(job.leaseEpoch()).isEqualTo(1L);
        assertThat(job.leaseOwner()).isEqualTo("worker-1");
        assertThat(job.leaseExpiresAt()).isEqualTo(expires);
    }
}
