package com.company.testgen.llm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-memory implementation of CostEventRepository.
 */
public class InMemoryCostEventRepository implements CostEventRepository {

    private final ConcurrentHashMap<UUID, List<CostEvent>> store = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    @Override
    public void insert(CostEvent event) {
        if (event == null || event.jobId() == null) {
            return;
        }
        CostEvent saved = event.id() == null
                ? new CostEvent(idGenerator.getAndIncrement(), event.jobId(), event.attemptId(), event.phase(),
                event.modelId(), event.tokenUsage(), event.estimatedCostUsd(), event.createdAt())
                : event;

        store.computeIfAbsent(event.jobId(), k -> new CopyOnWriteArrayList<>()).add(saved);
    }

    @Override
    public JobSpend spendFor(UUID jobId) {
        if (jobId == null) {
            return JobSpend.ZERO;
        }
        List<CostEvent> events = store.get(jobId);
        if (events == null || events.isEmpty()) {
            return JobSpend.ZERO;
        }

        int totalTokens = 0;
        BigDecimal totalUsd = BigDecimal.ZERO;
        for (CostEvent e : events) {
            if (e.tokenUsage() != null) {
                totalTokens += e.tokenUsage().totalTokens();
            }
            if (e.estimatedCostUsd() != null) {
                totalUsd = totalUsd.add(e.estimatedCostUsd());
            }
        }
        return new JobSpend(totalTokens, totalUsd);
    }

    @Override
    public List<CostEvent> findByJobId(UUID jobId) {
        if (jobId == null) {
            return List.of();
        }
        List<CostEvent> events = store.get(jobId);
        return events != null ? new ArrayList<>(events) : List.of();
    }
}
