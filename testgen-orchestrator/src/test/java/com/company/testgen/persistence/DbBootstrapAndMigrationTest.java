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
    void testV1InitSqlExistsAndContainsRequiredTables() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/db/migration/V1__init.sql")) {
            assertThat(is).isNotNull();
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql).contains("CREATE TABLE jobs");
            assertThat(sql).contains("CREATE TABLE retry_attempts");
            assertThat(sql).contains("CREATE TABLE validation_results");
            assertThat(sql).contains("CREATE TABLE cost_events");
            assertThat(sql).contains("idempotency_key       TEXT        NOT NULL UNIQUE");
            assertThat(sql).contains("lease_epoch           BIGINT      NOT NULL DEFAULT 0");
            assertThat(sql).contains("cache_read_tokens     INT  NOT NULL DEFAULT 0");
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
