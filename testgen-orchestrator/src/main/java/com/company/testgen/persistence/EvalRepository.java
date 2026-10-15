package com.company.testgen.persistence;

import org.jdbi.v3.core.Handle;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for managing eval harness runs and case results (§6.14, §8.2, §8.5, §8.6).
 */
public interface EvalRepository {

    void insertRun(EvalRunRecord run);

    void insertRun(Handle handle, EvalRunRecord run);

    void updateRun(EvalRunRecord run);

    void updateRun(Handle handle, EvalRunRecord run);

    Optional<EvalRunRecord> findRunById(UUID id);

    Optional<EvalRunRecord> findRunById(Handle handle, UUID id);

    Optional<EvalRunRecord> findBaseline(String suite, String modelId, String promptBundleHash);

    Optional<EvalRunRecord> findBaseline(Handle handle, String suite, String modelId, String promptBundleHash);

    List<EvalRunRecord> findBaselinesOlderThan(String suite, String modelId, Duration age);

    List<EvalRunRecord> findBaselinesOlderThan(Handle handle, String suite, String modelId, Duration age);

    void promoteBaseline(UUID runId);

    void promoteBaseline(Handle handle, UUID runId);

    void insertCaseResult(EvalCaseResultRecord caseResult);

    void insertCaseResult(Handle handle, EvalCaseResultRecord caseResult);

    void insertCaseResults(List<EvalCaseResultRecord> caseResults);

    void insertCaseResults(Handle handle, List<EvalCaseResultRecord> caseResults);

    List<EvalCaseResultRecord> findCaseResultsByRunId(UUID runId);

    List<EvalCaseResultRecord> findCaseResultsByRunId(Handle handle, UUID runId);

    List<EvalRunRecord> findAllRuns();

    List<EvalRunRecord> findAllRuns(Handle handle);
}
