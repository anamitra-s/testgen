package com.company.testgen.persistence;

import com.company.testgen.llm.CostEvent;
import com.company.testgen.llm.JobSpend;
import org.jdbi.v3.core.Handle;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory test double for {@link CostEventRepository}.
 */
public class InMemoryCostEventRepository implements CostEventRepository {

    private final Map<UUID, List<CostEvent>> storage = new ConcurrentHashMap<>();

    @Override
    public void insert(CostEvent event) {
        insert(null, event);
    }

    @Override
    public void insert(Handle handle, CostEvent event) {
        storage.computeIfAbsent(event.jobId(), k -> new CopyOnWriteArrayList<>()).add(event);
    }

    @Override
    public JobSpend spendFor(UUID jobId) {
        return spendFor(null, jobId);
    }

    @Override
    public JobSpend spendFor(Handle handle, UUID jobId) {
        List<CostEvent> events = findByJobId(handle, jobId);
        int totalTokens = 0;
        BigDecimal usd = BigDecimal.ZERO;
        for (CostEvent e : events) {
            if (e.tokenUsage() != null) {
                totalTokens += e.tokenUsage().totalTokens();
            }
            if (e.estimatedCostUsd() != null) {
                usd = usd.add(e.estimatedCostUsd());
            }
        }
        return new JobSpend(totalTokens, usd);
    }

    @Override
    public List<CostEvent> findByJobId(UUID jobId) {
        return findByJobId(null, jobId);
    }

    @Override
    public List<CostEvent> findByJobId(Handle handle, UUID jobId) {
        List<CostEvent> list = storage.get(jobId);
        if (list == null) return List.of();
        return list.stream()
                .sorted(Comparator.comparing(CostEvent::createdAt))
                .toList();
    }
}
