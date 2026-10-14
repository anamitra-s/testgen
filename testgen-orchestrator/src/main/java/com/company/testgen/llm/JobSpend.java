package com.company.testgen.llm;

import java.math.BigDecimal;

/**
 * Aggregated token and USD spend for a job (§6.10).
 */
public record JobSpend(
        int totalTokens,
        BigDecimal usd
) {
    public static final JobSpend ZERO = new JobSpend(0, BigDecimal.ZERO);

    public JobSpend {
        if (usd == null) {
            usd = BigDecimal.ZERO;
        }
    }
}
