package com.company.testgen.llm;

import java.util.List;
import java.util.UUID;

/**
 * Repository interface for recording and querying cost events (§6.10).
 */
public interface CostEventRepository {

    void insert(CostEvent event);

    JobSpend spendFor(UUID jobId);

    List<CostEvent> findByJobId(UUID jobId);
}
