package com.company.testgen.persistence;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBI 3 implementation of {@link EvalRepository} (§6.14, §8.2, §8.5, §8.6).
 */
public class JdbiEvalRepository implements EvalRepository {

    private static final String INSERT_RUN_SQL = """
            INSERT INTO eval_runs (
                id, suite, trigger, model_id, prompt_bundle_hash, orchestrator_version,
                git_ref, repeats, case_count, is_baseline,
                metrics, gate_outcome, gate_detail,
                started_at, finished_at
            ) VALUES (
                :id, :suite, :trigger, :modelId, :promptBundleHash, :orchestratorVersion,
                :gitRef, :repeats, :caseCount, :isBaseline,
                CAST(:metrics AS jsonb), :gateOutcome, CAST(:gateDetail AS jsonb),
                COALESCE(:startedAt, now()), :finishedAt
            )
            """;

    private static final String UPDATE_RUN_SQL = """
            UPDATE eval_runs SET
                suite = :suite,
                trigger = :trigger,
                model_id = :modelId,
                prompt_bundle_hash = :promptBundleHash,
                orchestrator_version = :orchestratorVersion,
                git_ref = :gitRef,
                repeats = :repeats,
                case_count = :caseCount,
                is_baseline = :isBaseline,
                metrics = CAST(:metrics AS jsonb),
                gate_outcome = :gateOutcome,
                gate_detail = CAST(:gateDetail AS jsonb),
                finished_at = :finishedAt
            WHERE id = :id
            """;

    private static final String SELECT_RUN_BY_ID_SQL = """
            SELECT * FROM eval_runs WHERE id = :id
            """;

    private static final String SELECT_BASELINE_SQL = """
            SELECT * FROM eval_runs
            WHERE suite = :suite AND model_id = :modelId AND prompt_bundle_hash = :promptBundleHash AND is_baseline = TRUE
            ORDER BY started_at DESC
            LIMIT 1
            """;

    private static final String SELECT_BASELINES_OLDER_THAN_SQL = """
            SELECT * FROM eval_runs
            WHERE suite = :suite AND model_id = :modelId AND is_baseline = TRUE AND started_at < :cutoff
            ORDER BY started_at ASC
            """;

    private static final String PROMOTE_BASELINE_SQL = """
            UPDATE eval_runs SET is_baseline = TRUE WHERE id = :runId
            """;

    private static final String INSERT_CASE_RESULT_SQL = """
            INSERT INTO eval_case_results (
                run_id, case_id, repeat_no, first_attempt_pass, attempts_used, escalated,
                locator_exact_matches, locator_strategy_matches, reference_locator_count,
                input_tokens, output_tokens, cache_read_tokens, duration_ms, failure_codes
            ) VALUES (
                :runId, :caseId, :repeatNo, :firstAttemptPass, :attemptsUsed, :escalated,
                :locatorExactMatches, :locatorStrategyMatches, :referenceLocatorCount,
                :inputTokens, :outputTokens, :cacheReadTokens, :durationMs, :failureCodes
            )
            """;

    private static final String SELECT_CASE_RESULTS_BY_RUN_SQL = """
            SELECT * FROM eval_case_results WHERE run_id = :runId ORDER BY case_id ASC, repeat_no ASC
            """;

    private static final String SELECT_ALL_RUNS_SQL = """
            SELECT * FROM eval_runs ORDER BY started_at DESC
            """;

    private final Jdbi jdbi;

    public JdbiEvalRepository(Jdbi jdbi) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi cannot be null");
    }

    public JdbiEvalRepository(Db db) {
        this(Objects.requireNonNull(db, "db cannot be null").jdbi());
    }

    @Override
    public void insertRun(EvalRunRecord run) {
        jdbi.useHandle(handle -> insertRun(handle, run));
    }

    @Override
    public void insertRun(Handle handle, EvalRunRecord run) {
        handle.createUpdate(INSERT_RUN_SQL)
                .bind("id", run.id())
                .bind("suite", run.suite())
                .bind("trigger", run.trigger())
                .bind("modelId", run.modelId())
                .bind("promptBundleHash", run.promptBundleHash())
                .bind("orchestratorVersion", run.orchestratorVersion())
                .bind("gitRef", run.gitRef())
                .bind("repeats", run.repeats())
                .bind("caseCount", run.caseCount())
                .bind("isBaseline", run.isBaseline())
                .bind("metrics", run.metricsJson() != null ? run.metricsJson() : "{}")
                .bind("gateOutcome", run.gateOutcome())
                .bind("gateDetail", run.gateDetailJson())
                .bind("startedAt", run.startedAt() != null ? Timestamp.from(run.startedAt()) : null)
                .bind("finishedAt", run.finishedAt() != null ? Timestamp.from(run.finishedAt()) : null)
                .execute();
    }

    @Override
    public void updateRun(EvalRunRecord run) {
        jdbi.useHandle(handle -> updateRun(handle, run));
    }

    @Override
    public void updateRun(Handle handle, EvalRunRecord run) {
        handle.createUpdate(UPDATE_RUN_SQL)
                .bind("id", run.id())
                .bind("suite", run.suite())
                .bind("trigger", run.trigger())
                .bind("modelId", run.modelId())
                .bind("promptBundleHash", run.promptBundleHash())
                .bind("orchestratorVersion", run.orchestratorVersion())
                .bind("gitRef", run.gitRef())
                .bind("repeats", run.repeats())
                .bind("caseCount", run.caseCount())
                .bind("isBaseline", run.isBaseline())
                .bind("metrics", run.metricsJson() != null ? run.metricsJson() : "{}")
                .bind("gateOutcome", run.gateOutcome())
                .bind("gateDetail", run.gateDetailJson())
                .bind("finishedAt", run.finishedAt() != null ? Timestamp.from(run.finishedAt()) : null)
                .execute();
    }

    @Override
    public Optional<EvalRunRecord> findRunById(UUID id) {
        return jdbi.withHandle(handle -> findRunById(handle, id));
    }

    @Override
    public Optional<EvalRunRecord> findRunById(Handle handle, UUID id) {
        return handle.createQuery(SELECT_RUN_BY_ID_SQL)
                .bind("id", id)
                .map(this::mapRunRow)
                .findOne();
    }

    @Override
    public Optional<EvalRunRecord> findBaseline(String suite, String modelId, String promptBundleHash) {
        return jdbi.withHandle(handle -> findBaseline(handle, suite, modelId, promptBundleHash));
    }

    @Override
    public Optional<EvalRunRecord> findBaseline(Handle handle, String suite, String modelId, String promptBundleHash) {
        return handle.createQuery(SELECT_BASELINE_SQL)
                .bind("suite", suite)
                .bind("modelId", modelId)
                .bind("promptBundleHash", promptBundleHash)
                .map(this::mapRunRow)
                .findOne();
    }

    @Override
    public List<EvalRunRecord> findBaselinesOlderThan(String suite, String modelId, Duration age) {
        return jdbi.withHandle(handle -> findBaselinesOlderThan(handle, suite, modelId, age));
    }

    @Override
    public List<EvalRunRecord> findBaselinesOlderThan(Handle handle, String suite, String modelId, Duration age) {
        Instant cutoff = Instant.now().minus(age);
        return handle.createQuery(SELECT_BASELINES_OLDER_THAN_SQL)
                .bind("suite", suite)
                .bind("modelId", modelId)
                .bind("cutoff", Timestamp.from(cutoff))
                .map(this::mapRunRow)
                .list();
    }

    @Override
    public void promoteBaseline(UUID runId) {
        jdbi.useHandle(handle -> promoteBaseline(handle, runId));
    }

    @Override
    public void promoteBaseline(Handle handle, UUID runId) {
        handle.createUpdate(PROMOTE_BASELINE_SQL)
                .bind("runId", runId)
                .execute();
    }

    @Override
    public void insertCaseResult(EvalCaseResultRecord caseResult) {
        jdbi.useHandle(handle -> insertCaseResult(handle, caseResult));
    }

    @Override
    public void insertCaseResult(Handle handle, EvalCaseResultRecord caseResult) {
        String[] codes = caseResult.failureCodes() != null
                ? caseResult.failureCodes().toArray(new String[0])
                : new String[0];

        handle.createUpdate(INSERT_CASE_RESULT_SQL)
                .bind("runId", caseResult.runId())
                .bind("caseId", caseResult.caseId())
                .bind("repeatNo", caseResult.repeatNo())
                .bind("firstAttemptPass", caseResult.firstAttemptPass())
                .bind("attemptsUsed", caseResult.attemptsUsed())
                .bind("escalated", caseResult.escalated())
                .bind("locatorExactMatches", caseResult.locatorExactMatches())
                .bind("locatorStrategyMatches", caseResult.locatorStrategyMatches())
                .bind("referenceLocatorCount", caseResult.referenceLocatorCount())
                .bind("inputTokens", caseResult.inputTokens())
                .bind("outputTokens", caseResult.outputTokens())
                .bind("cacheReadTokens", caseResult.cacheReadTokens())
                .bind("durationMs", caseResult.durationMs())
                .bind("failureCodes", codes)
                .execute();
    }

    @Override
    public void insertCaseResults(List<EvalCaseResultRecord> caseResults) {
        if (caseResults == null || caseResults.isEmpty()) {
            return;
        }
        jdbi.useHandle(handle -> insertCaseResults(handle, caseResults));
    }

    @Override
    public void insertCaseResults(Handle handle, List<EvalCaseResultRecord> caseResults) {
        if (caseResults == null || caseResults.isEmpty()) {
            return;
        }
        PreparedBatch batch = handle.prepareBatch(INSERT_CASE_RESULT_SQL);
        for (EvalCaseResultRecord c : caseResults) {
            String[] codes = c.failureCodes() != null
                    ? c.failureCodes().toArray(new String[0])
                    : new String[0];

            batch.bind("runId", c.runId())
                    .bind("caseId", c.caseId())
                    .bind("repeatNo", c.repeatNo())
                    .bind("firstAttemptPass", c.firstAttemptPass())
                    .bind("attemptsUsed", c.attemptsUsed())
                    .bind("escalated", c.escalated())
                    .bind("locatorExactMatches", c.locatorExactMatches())
                    .bind("locatorStrategyMatches", c.locatorStrategyMatches())
                    .bind("referenceLocatorCount", c.referenceLocatorCount())
                    .bind("inputTokens", c.inputTokens())
                    .bind("outputTokens", c.outputTokens())
                    .bind("cacheReadTokens", c.cacheReadTokens())
                    .bind("durationMs", c.durationMs())
                    .bind("failureCodes", codes)
                    .add();
        }
        batch.execute();
    }

    @Override
    public List<EvalCaseResultRecord> findCaseResultsByRunId(UUID runId) {
        return jdbi.withHandle(handle -> findCaseResultsByRunId(handle, runId));
    }

    @Override
    public List<EvalCaseResultRecord> findCaseResultsByRunId(Handle handle, UUID runId) {
        return handle.createQuery(SELECT_CASE_RESULTS_BY_RUN_SQL)
                .bind("runId", runId)
                .map(this::mapCaseResultRow)
                .list();
    }

    @Override
    public List<EvalRunRecord> findAllRuns() {
        return jdbi.withHandle(this::findAllRuns);
    }

    @Override
    public List<EvalRunRecord> findAllRuns(Handle handle) {
        return handle.createQuery(SELECT_ALL_RUNS_SQL)
                .map(this::mapRunRow)
                .list();
    }

    private EvalRunRecord mapRunRow(ResultSet rs, StatementContext ctx) throws SQLException {
        Timestamp startedAtTs = rs.getTimestamp("started_at");
        Timestamp finishedAtTs = rs.getTimestamp("finished_at");

        return new EvalRunRecord(
                rs.getObject("id", UUID.class),
                rs.getString("suite"),
                rs.getString("trigger"),
                rs.getString("model_id"),
                rs.getString("prompt_bundle_hash"),
                rs.getString("orchestrator_version"),
                rs.getString("git_ref"),
                rs.getInt("repeats"),
                rs.getInt("case_count"),
                rs.getBoolean("is_baseline"),
                rs.getString("metrics"),
                rs.getString("gate_outcome"),
                rs.getString("gate_detail"),
                startedAtTs != null ? startedAtTs.toInstant() : null,
                finishedAtTs != null ? finishedAtTs.toInstant() : null
        );
    }

    private EvalCaseResultRecord mapCaseResultRow(ResultSet rs, StatementContext ctx) throws SQLException {
        Array codesArray = rs.getArray("failure_codes");
        List<String> codes = List.of();
        if (codesArray != null) {
            Object arr = codesArray.getArray();
            if (arr instanceof String[] strArr) {
                codes = Arrays.asList(strArr);
            } else if (arr instanceof Object[] objArr) {
                codes = Arrays.stream(objArr).map(Object::toString).toList();
            }
        }

        return new EvalCaseResultRecord(
                rs.getLong("id"),
                rs.getObject("run_id", UUID.class),
                rs.getString("case_id"),
                rs.getInt("repeat_no"),
                rs.getBoolean("first_attempt_pass"),
                rs.getInt("attempts_used"),
                rs.getBoolean("escalated"),
                rs.getInt("locator_exact_matches"),
                rs.getInt("locator_strategy_matches"),
                rs.getInt("reference_locator_count"),
                rs.getInt("input_tokens"),
                rs.getInt("output_tokens"),
                rs.getInt("cache_read_tokens"),
                rs.getInt("duration_ms"),
                codes
        );
    }
}
