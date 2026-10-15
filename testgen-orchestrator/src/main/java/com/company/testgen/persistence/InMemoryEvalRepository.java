package com.company.testgen.persistence;

import org.jdbi.v3.core.Handle;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory test double for {@link EvalRepository}.
 */
public class InMemoryEvalRepository implements EvalRepository {

    private final Map<UUID, EvalRunRecord> runs = new ConcurrentHashMap<>();
    private final List<EvalCaseResultRecord> caseResults = new CopyOnWriteArrayList<>();
    private final AtomicLong caseSequence = new AtomicLong(1L);

    @Override
    public void insertRun(EvalRunRecord run) {
        insertRun(null, run);
    }

    @Override
    public void insertRun(Handle handle, EvalRunRecord run) {
        if (run == null) {
            return;
        }
        runs.put(run.id(), run);
    }

    @Override
    public void updateRun(EvalRunRecord run) {
        updateRun(null, run);
    }

    @Override
    public void updateRun(Handle handle, EvalRunRecord run) {
        if (run == null) {
            return;
        }
        runs.put(run.id(), run);
    }

    @Override
    public Optional<EvalRunRecord> findRunById(UUID id) {
        return findRunById(null, id);
    }

    @Override
    public Optional<EvalRunRecord> findRunById(Handle handle, UUID id) {
        return Optional.ofNullable(runs.get(id));
    }

    @Override
    public Optional<EvalRunRecord> findBaseline(String suite, String modelId, String promptBundleHash) {
        return findBaseline(null, suite, modelId, promptBundleHash);
    }

    @Override
    public Optional<EvalRunRecord> findBaseline(Handle handle, String suite, String modelId, String promptBundleHash) {
        return runs.values().stream()
                .filter(r -> r.isBaseline()
                        && r.suite().equalsIgnoreCase(suite)
                        && r.modelId().equalsIgnoreCase(modelId)
                        && r.promptBundleHash().equalsIgnoreCase(promptBundleHash))
                .max(Comparator.comparing(r -> r.startedAt() != null ? r.startedAt() : Instant.EPOCH));
    }

    @Override
    public List<EvalRunRecord> findBaselinesOlderThan(String suite, String modelId, Duration age) {
        return findBaselinesOlderThan(null, suite, modelId, age);
    }

    @Override
    public List<EvalRunRecord> findBaselinesOlderThan(Handle handle, String suite, String modelId, Duration age) {
        Instant cutoff = Instant.now().minus(age);
        return runs.values().stream()
                .filter(r -> r.isBaseline()
                        && r.suite().equalsIgnoreCase(suite)
                        && r.modelId().equalsIgnoreCase(modelId)
                        && r.startedAt() != null
                        && r.startedAt().isBefore(cutoff))
                .sorted(Comparator.comparing(EvalRunRecord::startedAt))
                .toList();
    }

    @Override
    public void promoteBaseline(UUID runId) {
        promoteBaseline(null, runId);
    }

    @Override
    public void promoteBaseline(Handle handle, UUID runId) {
        EvalRunRecord target = runs.get(runId);
        if (target != null) {
            runs.replaceAll((id, r) -> {
                if (r.isBaseline()
                        && r.suite().equalsIgnoreCase(target.suite())
                        && r.modelId().equalsIgnoreCase(target.modelId())
                        && r.promptBundleHash().equalsIgnoreCase(target.promptBundleHash())
                        && !r.id().equals(runId)) {
                    return new EvalRunRecord(
                            r.id(), r.suite(), r.trigger(), r.modelId(), r.promptBundleHash(),
                            r.orchestratorVersion(), r.gitRef(), r.repeats(), r.caseCount(),
                            false, r.metricsJson(), r.gateOutcome(), r.gateDetailJson(),
                            r.startedAt(), r.finishedAt()
                    );
                }
                return r;
            });
        }
        runs.computeIfPresent(runId, (id, r) -> new EvalRunRecord(
                r.id(), r.suite(), r.trigger(), r.modelId(), r.promptBundleHash(),
                r.orchestratorVersion(), r.gitRef(), r.repeats(), r.caseCount(),
                true, r.metricsJson(), r.gateOutcome(), r.gateDetailJson(),
                r.startedAt(), r.finishedAt()
        ));
    }

    @Override
    public void insertCaseResult(EvalCaseResultRecord caseResult) {
        insertCaseResult(null, caseResult);
    }

    @Override
    public void insertCaseResult(Handle handle, EvalCaseResultRecord caseResult) {
        if (caseResult == null) {
            return;
        }
        long id = caseResult.id() != null ? caseResult.id() : caseSequence.getAndIncrement();
        EvalCaseResultRecord record = new EvalCaseResultRecord(
                id,
                caseResult.runId(),
                caseResult.caseId(),
                caseResult.repeatNo(),
                caseResult.firstAttemptPass(),
                caseResult.attemptsUsed(),
                caseResult.escalated(),
                caseResult.locatorExactMatches(),
                caseResult.locatorStrategyMatches(),
                caseResult.referenceLocatorCount(),
                caseResult.inputTokens(),
                caseResult.outputTokens(),
                caseResult.cacheReadTokens(),
                caseResult.durationMs(),
                caseResult.failureCodes()
        );
        caseResults.add(record);
    }

    @Override
    public void insertCaseResults(List<EvalCaseResultRecord> caseResults) {
        insertCaseResults(null, caseResults);
    }

    @Override
    public void insertCaseResults(Handle handle, List<EvalCaseResultRecord> caseResults) {
        if (caseResults == null) {
            return;
        }
        for (EvalCaseResultRecord cr : caseResults) {
            insertCaseResult(handle, cr);
        }
    }

    @Override
    public List<EvalCaseResultRecord> findCaseResultsByRunId(UUID runId) {
        return findCaseResultsByRunId(null, runId);
    }

    @Override
    public List<EvalCaseResultRecord> findCaseResultsByRunId(Handle handle, UUID runId) {
        return caseResults.stream()
                .filter(cr -> cr.runId().equals(runId))
                .sorted(Comparator.comparing(EvalCaseResultRecord::repeatNo))
                .toList();
    }

    @Override
    public List<EvalRunRecord> findAllRuns() {
        return findAllRuns(null);
    }

    @Override
    public List<EvalRunRecord> findAllRuns(Handle handle) {
        return runs.values().stream()
                .sorted(Comparator.comparing(r -> r.startedAt() != null ? r.startedAt() : Instant.EPOCH))
                .toList();
    }
}
