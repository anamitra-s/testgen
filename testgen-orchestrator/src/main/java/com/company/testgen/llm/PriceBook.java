package com.company.testgen.llm;

import com.company.testgen.model.TokenUsage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Price book for per-model LLM pricing ($/Mtok) (§6.10).
 */
public class PriceBook {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    public record ModelPricing(
            BigDecimal inputPricePerMillion,
            BigDecimal outputPricePerMillion,
            BigDecimal cacheCreationPricePerMillion,
            BigDecimal cacheReadPricePerMillion
    ) {
        public static final ModelPricing DEFAULT_SONNET = new ModelPricing(
                new BigDecimal("3.00"),
                new BigDecimal("15.00"),
                new BigDecimal("3.75"),
                new BigDecimal("0.30")
        );
    }

    private final Map<String, ModelPricing> pricingMap = new ConcurrentHashMap<>();
    private final ModelPricing fallbackPricing;

    public PriceBook() {
        this(Map.of(
                "claude-3-5-sonnet-20241022", ModelPricing.DEFAULT_SONNET,
                "claude-3-5-sonnet-latest", ModelPricing.DEFAULT_SONNET,
                "claude-sonnet-5", ModelPricing.DEFAULT_SONNET
        ), ModelPricing.DEFAULT_SONNET);
    }

    public PriceBook(Map<String, ModelPricing> pricingMap, ModelPricing fallbackPricing) {
        if (pricingMap != null) {
            this.pricingMap.putAll(pricingMap);
        }
        this.fallbackPricing = fallbackPricing != null ? fallbackPricing : ModelPricing.DEFAULT_SONNET;
    }

    public BigDecimal cost(String modelId, TokenUsage u) {
        if (u == null) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }
        ModelPricing p = (modelId != null && pricingMap.containsKey(modelId))
                ? pricingMap.get(modelId)
                : fallbackPricing;

        BigDecimal inputCost = p.inputPricePerMillion()
                .multiply(BigDecimal.valueOf(u.inputTokens()));
        BigDecimal outputCost = p.outputPricePerMillion()
                .multiply(BigDecimal.valueOf(u.outputTokens()));
        BigDecimal cacheCreationCost = p.cacheCreationPricePerMillion()
                .multiply(BigDecimal.valueOf(u.cacheCreationTokens()));
        BigDecimal cacheReadCost = p.cacheReadPricePerMillion()
                .multiply(BigDecimal.valueOf(u.cacheReadTokens()));

        BigDecimal totalCost = inputCost.add(outputCost).add(cacheCreationCost).add(cacheReadCost);
        return totalCost.divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
    }
}
