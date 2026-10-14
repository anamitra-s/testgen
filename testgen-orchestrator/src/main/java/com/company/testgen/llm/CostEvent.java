package com.company.testgen.llm;

import com.company.testgen.model.Phase;
import com.company.testgen.model.TokenUsage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Audit record of LLM token usage and estimated USD cost (§6.10, §6.14).
 */
public record CostEvent(
        Long id,
        UUID jobId,
        Long attemptId,
        Phase phase,
        String modelId,
        TokenUsage tokenUsage,
        BigDecimal estimatedCostUsd,
        Instant createdAt
) {
    public CostEvent(UUID jobId, Long attemptId, Phase phase, String modelId, TokenUsage tokenUsage, BigDecimal estimatedCostUsd) {
        this(null, jobId, attemptId, phase, modelId, tokenUsage, estimatedCostUsd, Instant.now());
    }
}
