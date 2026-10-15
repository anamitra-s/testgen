package com.company.testgen.persistence;

import com.company.testgen.llm.CostEvent;
import com.company.testgen.llm.JobSpend;
import org.jdbi.v3.core.Handle;

import java.util.List;
import java.util.UUID;

/**
 * Persistence repository interface for LLM cost events and token usage audit (§6.10, §6.14).
 * Extends {@link com.company.testgen.llm.CostEventRepository} with transactional handle overloads.
 */
public interface CostEventRepository extends com.company.testgen.llm.CostEventRepository {

    void insert(Handle handle, CostEvent event);

    JobSpend spendFor(Handle handle, UUID jobId);

    List<CostEvent> findByJobId(Handle handle, UUID jobId);
}
