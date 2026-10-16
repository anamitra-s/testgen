package com.company.testgen.persistence;

import com.company.testgen.model.JobRecord;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * JDBI RowMapper for converting SQL ResultSet rows to {@link JobRecord} (§6.14, §9.7).
 */
public class JobRecordMapper implements RowMapper<JobRecord> {

    public static final JobRecordMapper INSTANCE = new JobRecordMapper();

    @Override
    public JobRecord map(ResultSet rs, StatementContext ctx) throws SQLException {
        Timestamp leaseExpires = rs.getTimestamp("lease_expires_at");
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        int mrIidVal = rs.getInt("mr_iid");
        Integer mrIid = rs.wasNull() ? null : mrIidVal;

        String buildUrl = null;
        try {
            buildUrl = rs.getString("build_url");
        } catch (SQLException ignored) {}

        return new JobRecord(
                rs.getObject("id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("target_area"),
                rs.getString("test_description"),
                rs.getString("requester"),
                rs.getString("recording_branch"),
                rs.getString("state"),
                rs.getString("confidence_tier"),
                rs.getString("failure_reason"),
                rs.getString("prompt_bundle_hash"),
                rs.getString("model_id"),
                rs.getString("orchestrator_version"),
                rs.getString("recorder_version"),
                rs.getString("base_commit_sha"),
                rs.getString("lease_owner"),
                rs.getLong("lease_epoch"),
                leaseExpires != null ? leaseExpires.toInstant() : null,
                buildUrl,
                rs.getString("redaction_report"),
                mrIid,
                rs.getString("mr_url"),
                createdAt != null ? createdAt.toInstant() : null,
                updatedAt != null ? updatedAt.toInstant() : null,
                completedAt != null ? completedAt.toInstant() : null
        );
    }
}
