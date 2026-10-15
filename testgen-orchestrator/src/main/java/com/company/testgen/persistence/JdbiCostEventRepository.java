package com.company.testgen.persistence;

import com.company.testgen.llm.CostEvent;
import com.company.testgen.llm.JobSpend;
import com.company.testgen.model.Phase;
import com.company.testgen.model.TokenUsage;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.StatementContext;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * JDBI 3 implementation of {@link CostEventRepository} (§6.10, §6.14).
 */
public class JdbiCostEventRepository implements CostEventRepository {

    private static final String INSERT_SQL = """
            INSERT INTO cost_events (
                job_id, attempt_id, phase, model_id,
                input_tokens, output_tokens, cache_creation_tokens, cache_read_tokens,
                estimated_cost_usd, created_at
            ) VALUES (
                :jobId, :attemptId, :phase, :modelId,
                :inputTokens, :outputTokens, :cacheCreationTokens, :cacheReadTokens,
                :estimatedCostUsd, COALESCE(:createdAt, now())
            )
            """;

    private static final String SPEND_FOR_SQL = """
            SELECT
                COALESCE(SUM(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens), 0) AS total_tokens,
                COALESCE(SUM(estimated_cost_usd), 0.0) AS total_cost
            FROM cost_events
            WHERE job_id = :jobId
            """;

    private static final String SELECT_BY_JOB_SQL = """
            SELECT * FROM cost_events WHERE job_id = :jobId ORDER BY created_at ASC, id ASC
            """;

    private final Jdbi jdbi;

    public JdbiCostEventRepository(Jdbi jdbi) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi cannot be null");
    }

    public JdbiCostEventRepository(Db db) {
        this(Objects.requireNonNull(db, "db cannot be null").jdbi());
    }

    @Override
    public void insert(CostEvent event) {
        jdbi.useHandle(handle -> insert(handle, event));
    }

    @Override
    public void insert(Handle handle, CostEvent event) {
        if (event == null || event.jobId() == null) {
            return;
        }
        TokenUsage usage = event.tokenUsage() != null
                ? event.tokenUsage()
                : new TokenUsage(0, 0, 0, 0);

        handle.createUpdate(INSERT_SQL)
                .bind("jobId", event.jobId())
                .bind("attemptId", event.attemptId())
                .bind("phase", event.phase() != null ? event.phase().name() : "RESOLVE")
                .bind("modelId", event.modelId())
                .bind("inputTokens", usage.inputTokens())
                .bind("outputTokens", usage.outputTokens())
                .bind("cacheCreationTokens", usage.cacheCreationTokens())
                .bind("cacheReadTokens", usage.cacheReadTokens())
                .bind("estimatedCostUsd", event.estimatedCostUsd())
                .bind("createdAt", event.createdAt() != null ? Timestamp.from(event.createdAt()) : null)
                .execute();
    }

    @Override
    public JobSpend spendFor(UUID jobId) {
        return jdbi.withHandle(handle -> spendFor(handle, jobId));
    }

    @Override
    public JobSpend spendFor(Handle handle, UUID jobId) {
        if (jobId == null) {
            return JobSpend.ZERO;
        }
        return handle.createQuery(SPEND_FOR_SQL)
                .bind("jobId", jobId)
                .map((rs, ctx) -> {
                    int totalTokens = rs.getInt("total_tokens");
                    BigDecimal totalCost = rs.getBigDecimal("total_cost");
                    return new JobSpend(totalTokens, totalCost != null ? totalCost : BigDecimal.ZERO);
                })
                .findOne()
                .orElse(JobSpend.ZERO);
    }

    @Override
    public List<CostEvent> findByJobId(UUID jobId) {
        return jdbi.withHandle(handle -> findByJobId(handle, jobId));
    }

    @Override
    public List<CostEvent> findByJobId(Handle handle, UUID jobId) {
        if (jobId == null) {
            return List.of();
        }
        return handle.createQuery(SELECT_BY_JOB_SQL)
                .bind("jobId", jobId)
                .map(this::mapRow)
                .list();
    }

    private CostEvent mapRow(ResultSet rs, StatementContext ctx) throws SQLException {
        Timestamp createdAtTs = rs.getTimestamp("created_at");
        Long attemptId = rs.getObject("attempt_id") != null ? rs.getLong("attempt_id") : null;
        String phaseStr = rs.getString("phase");
        Phase phase = null;
        if (phaseStr != null) {
            try {
                phase = Phase.valueOf(phaseStr.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }
        TokenUsage usage = new TokenUsage(
                rs.getInt("input_tokens"),
                rs.getInt("output_tokens"),
                rs.getInt("cache_creation_tokens"),
                rs.getInt("cache_read_tokens")
        );

        return new CostEvent(
                rs.getLong("id"),
                rs.getObject("job_id", UUID.class),
                attemptId,
                phase,
                rs.getString("model_id"),
                usage,
                rs.getBigDecimal("estimated_cost_usd"),
                createdAtTs != null ? createdAtTs.toInstant() : null
        );
    }
}
