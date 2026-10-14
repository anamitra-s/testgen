package com.company.testgen.llm;

import java.math.BigDecimal;

/**
 * Thrown when pre-flight estimation or current spend breaches the token or USD budget (§6.10).
 */
public class BudgetExceededException extends RuntimeException {

    private final String reason;
    private final int currentTokens;
    private final int estimatedTokens;
    private final int maxTokens;
    private final BigDecimal currentUsd;
    private final BigDecimal maxUsd;

    public BudgetExceededException(String reason, int currentTokens, int estimatedTokens, int maxTokens) {
        super(String.format("Job token budget exceeded: current=%d, estimated=%d, total=%d > limit=%d",
                currentTokens, estimatedTokens, (currentTokens + estimatedTokens), maxTokens));
        this.reason = reason;
        this.currentTokens = currentTokens;
        this.estimatedTokens = estimatedTokens;
        this.maxTokens = maxTokens;
        this.currentUsd = null;
        this.maxUsd = null;
    }

    public BudgetExceededException(String reason, BigDecimal currentUsd, BigDecimal maxUsd) {
        super(String.format("Job USD budget exceeded: spend=$%s >= cap=$%s", currentUsd, maxUsd));
        this.reason = reason;
        this.currentTokens = 0;
        this.estimatedTokens = 0;
        this.maxTokens = 0;
        this.currentUsd = currentUsd;
        this.maxUsd = maxUsd;
    }

    public String reason() {
        return reason;
    }

    public int currentTokens() {
        return currentTokens;
    }

    public int estimatedTokens() {
        return estimatedTokens;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public BigDecimal currentUsd() {
        return currentUsd;
    }

    public BigDecimal maxUsd() {
        return maxUsd;
    }
}
