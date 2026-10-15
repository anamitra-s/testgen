package com.company.testgen.persistence;

import com.company.testgen.idempotency.EffectType;
import com.company.testgen.llm.CostEvent;
import com.company.testgen.llm.JobSpend;
import com.company.testgen.model.Phase;
import com.company.testgen.model.Severity;
import com.company.testgen.model.TokenUsage;
import com.company.testgen.model.ValidationResult;
import com.company.testgen.model.ValidationViolation;
import com.company.testgen.model.VerificationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceLayerTest {

    @Test
    @DisplayName("RetryAttemptRepository records attempt lifecycle, completion, and retrieval")
    void testRetryAttemptRepository() {
        RetryAttemptRepository repo = new InMemoryRetryAttemptRepository();
        UUID jobId = UUID.randomUUID();

        Long attempt0 = repo.begin(jobId, 0, Phase.RESOLVE);
        assertThat(attempt0).isNotNull();

        Optional<RetryAttemptRecord> rec0 = repo.findById(attempt0);
        assertThat(rec0).isPresent();
        assertThat(rec0.get().attemptNo()).isEqualTo(0);
        assertThat(rec0.get().phase()).isEqualTo("RESOLVE");
        assertThat(rec0.get().passed()).isNull();

        repo.finish(attempt0, true, null);
        Optional<RetryAttemptRecord> rec0Completed = repo.findById(attempt0);
        assertThat(rec0Completed.get().passed()).isTrue();
        assertThat(rec0Completed.get().finishedAt()).isNotNull();

        Long attempt1 = repo.begin(jobId, 1, Phase.GENERATE);
        repo.finish(attempt1, false, "Compilation failed: line 42");

        List<RetryAttemptRecord> attempts = repo.findByJobId(jobId);
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(1).feedbackSent()).isEqualTo("Compilation failed: line 42");
    }

    @Test
    @DisplayName("ValidationResultRepository persists and queries violations by attempt")
    void testValidationResultRepository() {
        ValidationResultRepository repo = new InMemoryValidationResultRepository();
        long attemptId = 42L;

        ValidationViolation v1 = new ValidationViolation(
                "getByTestId(\"submit\")",
                "btn-1",
                "NOT_UNIQUE",
                2,
                Severity.BLOCKING,
                "Matched 2 elements on current page"
        );
        ValidationViolation v2 = new ValidationViolation(
                "getByText(\"Welcome\")",
                "txt-1",
                "TEXT_STRATEGY_LOW_CONFIDENCE",
                1,
                Severity.ADVISORY,
                "Text-based selector is brittle"
        );

        ValidationResult result = new ValidationResult(List.of(v1, v2));
        repo.saveAll(attemptId, "L1", result);

        List<ValidationViolation> records = repo.findByAttemptId(attemptId);
        assertThat(records).hasSize(2);
        assertThat(records.get(0).severity()).isEqualTo(Severity.BLOCKING);
        assertThat(records.get(1).severity()).isEqualTo(Severity.ADVISORY);

        List<ValidationResultRecord> notUniqueReasons = repo.findByReasonCode("NOT_UNIQUE");
        assertThat(notUniqueReasons).hasSize(1);
        assertThat(notUniqueReasons.get(0).matchCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("CostEventRepository records token events and computes aggregate spend")
    void testCostEventRepository() {
        CostEventRepository repo = new InMemoryCostEventRepository();
        UUID jobId = UUID.randomUUID();

        TokenUsage u1 = new TokenUsage(1000, 200, 500, 2000);
        CostEvent e1 = new CostEvent(jobId, 1L, Phase.RESOLVE, "claude-sonnet-5", u1, BigDecimal.valueOf(0.015));
        repo.insert(e1);

        TokenUsage u2 = new TokenUsage(2000, 400, 0, 3000);
        CostEvent e2 = new CostEvent(jobId, 2L, Phase.GENERATE, "claude-sonnet-5", u2, BigDecimal.valueOf(0.025));
        repo.insert(e2);

        List<CostEvent> events = repo.findByJobId(jobId);
        assertThat(events).hasSize(2);

        JobSpend spend = repo.spendFor(jobId);
        assertThat(spend.totalTokens()).isEqualTo(u1.totalTokens() + u2.totalTokens());
        assertThat(spend.usd()).isEqualByComparingTo("0.040");
    }

    @Test
    @DisplayName("VerificationRepository records L2 test outcomes and retrieves latest run")
    void testVerificationRepository() {
        VerificationRepository repo = new InMemoryVerificationRepository();
        UUID jobId = UUID.randomUUID();

        VerificationResult res1 = VerificationResult.failed(
                "AssertionError: Expected [Dashboard] but found [Login]",
                45000,
                false
        );
        repo.save(jobId, res1);

        VerificationResult res2 = VerificationResult.passed(38000);
        repo.save(jobId, res2);

        List<VerificationRecord> runs = repo.findByJobId(jobId);
        assertThat(runs).hasSize(2);

        Optional<VerificationRecord> latest = repo.findLatestByJobId(jobId);
        assertThat(latest).isPresent();
        assertThat(latest.get().outcome()).isEqualTo("PASSED");
        assertThat(latest.get().durationMs()).isEqualTo(38000);
    }

    @Test
    @DisplayName("EffectRepository tracks exactly-once external effects: reserve, attempt, confirm, abandon")
    void testEffectRepository() {
        EffectRepository repo = new InMemoryEffectRepository();
        UUID jobId = UUID.randomUUID();
        String branchKey = "testgen/billing/" + jobId;

        Optional<EffectRecord> initial = repo.find(jobId, EffectType.BRANCH, branchKey);
        assertThat(initial).isEmpty();

        // 1. Reserve
        repo.reserve(jobId, EffectType.BRANCH, branchKey, 1L);
        Optional<EffectRecord> reserved = repo.find(jobId, EffectType.BRANCH, branchKey);
        assertThat(reserved).isPresent();
        assertThat(reserved.get().status()).isEqualTo(EffectStatus.PENDING);
        assertThat(reserved.get().leaseEpoch()).isEqualTo(1L);

        // 2. Increment attempts on retry
        repo.incrementAttempts(jobId, EffectType.BRANCH, branchKey);
        assertThat(repo.find(jobId, EffectType.BRANCH, branchKey).get().attempts()).isEqualTo(1);

        // 3. Confirm with external ID
        repo.confirm(jobId, EffectType.BRANCH, branchKey, "branch-123");
        Optional<EffectRecord> confirmed = repo.find(jobId, EffectType.BRANCH, branchKey);
        assertThat(confirmed.get().status()).isEqualTo(EffectStatus.CONFIRMED);
        assertThat(confirmed.get().externalId()).isEqualTo("branch-123");
        assertThat(confirmed.get().confirmedAt()).isNotNull();

        // Test abandon
        String mrKey = "mr/" + jobId;
        repo.reserve(jobId, EffectType.MERGE_REQUEST, mrKey, 1L);
        repo.abandon(jobId, EffectType.MERGE_REQUEST, mrKey, "NOT_FOUND_AFTER_GRACE");
        assertThat(repo.find(jobId, EffectType.MERGE_REQUEST, mrKey).get().status()).isEqualTo(EffectStatus.ABANDONED);
    }

    @Test
    @DisplayName("EvalRepository manages eval runs, baseline discovery, and baseline promotion")
    void testEvalRepository() {
        EvalRepository repo = new InMemoryEvalRepository();
        UUID run1 = UUID.randomUUID();

        EvalRunRecord candidateRun = new EvalRunRecord(
                run1, "golden", "PR", "claude-sonnet-5", "bundle-hash-1", "1.0.0", "refs/heads/feature",
                3, 30, false, "{\"pass_rate\": 0.85}", "PASS", "{}",
                Instant.now().minus(Duration.ofHours(2)), Instant.now().minus(Duration.ofHours(1))
        );
        repo.insertRun(candidateRun);

        assertThat(repo.findBaseline("golden", "claude-sonnet-5", "bundle-hash-1")).isEmpty();

        repo.promoteBaseline(run1);
        Optional<EvalRunRecord> baseline = repo.findBaseline("golden", "claude-sonnet-5", "bundle-hash-1");
        assertThat(baseline).isPresent();
        assertThat(baseline.get().id()).isEqualTo(run1);
        assertThat(baseline.get().isBaseline()).isTrue();

        UUID run2 = UUID.randomUUID();
        EvalRunRecord run2Record = new EvalRunRecord(
                run2, "golden", "NIGHTLY", "claude-sonnet-5", "bundle-hash-1", "1.0.1", "refs/heads/main",
                3, 30, false, "{\"pass_rate\": 0.90}", "PASS", "{}",
                Instant.now().minus(Duration.ofMinutes(30)), Instant.now()
        );
        repo.insertRun(run2Record);
        repo.promoteBaseline(run2);

        Optional<EvalRunRecord> newBaseline = repo.findBaseline("golden", "claude-sonnet-5", "bundle-hash-1");
        assertThat(newBaseline).isPresent();
        assertThat(newBaseline.get().id()).isEqualTo(run2);

        // run1 should no longer be baseline
        Optional<EvalRunRecord> oldRun = repo.findRunById(run1);
        assertThat(oldRun.get().isBaseline()).isFalse();
    }

    @Test
    @DisplayName("TargetAreaLock acquires and releases PostgreSQL session advisory lock")
    void testTargetAreaLock() throws Exception {
        CopyOnWriteArrayList<String> executedStatements = new CopyOnWriteArrayList<>();
        AtomicBoolean connectionClosed = new AtomicBoolean(false);

        Connection fakeConn = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("prepareStatement".equals(methodName)) {
                        String sql = (String) args[0];
                        return Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[]{PreparedStatement.class},
                                (stmtProxy, stmtMethod, stmtArgs) -> {
                                    String smName = stmtMethod.getName();
                                    if ("setLong".equals(smName)) {
                                        executedStatements.add("bind:" + stmtArgs[1]);
                                    } else if ("execute".equals(smName)) {
                                        executedStatements.add("exec:" + sql.trim());
                                        return true;
                                    } else if ("close".equals(smName)) {
                                        executedStatements.add("close-stmt");
                                    }
                                    return defaultValue(stmtMethod.getReturnType());
                                }
                        );
                    } else if ("close".equals(methodName)) {
                        connectionClosed.set(true);
                        executedStatements.add("close-conn");
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        DataSource fakeDs = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        return fakeConn;
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        TargetAreaLock areaLock = new TargetAreaLock(fakeDs);
        try (var handle = areaLock.acquire("dashboard")) {
            assertThat(handle).isNotNull();
            assertThat(executedStatements).anyMatch(s -> s.contains("pg_advisory_lock"));
        }

        assertThat(executedStatements).anyMatch(s -> s.contains("pg_advisory_unlock"));
        assertThat(connectionClosed.get()).isTrue();
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        return null;
    }
}
