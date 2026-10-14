package com.company.testgen.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compliance audit report produced by the redaction engine (§6.9, §6.14).
 * Persisted to the {@code jobs.redaction_report} JSONB column to answer
 * "what left our network on job X" without re-deriving anything.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedactionReport(
        Map<String, Integer> rulesApplied,
        Map<String, Integer> attributesDropped,
        int urlsNormalized,
        int textsTruncated,
        String payloadSha256,
        String rulesVersion
) {
    public RedactionReport {
        if (rulesApplied == null) {
            rulesApplied = Map.of();
        }
        if (attributesDropped == null) {
            attributesDropped = Map.of();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Map<String, Integer> rulesApplied = new ConcurrentHashMap<>();
        private final Map<String, Integer> attributesDropped = new ConcurrentHashMap<>();
        private final AtomicInteger urlsNormalized = new AtomicInteger();
        private final AtomicInteger textsTruncated = new AtomicInteger();
        private String payloadSha256;
        private String rulesVersion = "redaction-v3";

        public Builder ruleApplied(String ruleName) {
            if (ruleName != null) {
                rulesApplied.merge(ruleName, 1, Integer::sum);
            }
            return this;
        }

        public Builder attributeDropped(String attributeName) {
            if (attributeName != null) {
                attributesDropped.merge(attributeName.toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
            }
            return this;
        }

        public Builder dropped(String attributeName) {
            return attributeDropped(attributeName);
        }

        public Builder urlNormalized() {
            urlsNormalized.incrementAndGet();
            return this;
        }

        public Builder textTruncated() {
            textsTruncated.incrementAndGet();
            return this;
        }

        public Builder payloadSha256(String payloadSha256) {
            this.payloadSha256 = payloadSha256;
            return this;
        }

        public Builder rulesVersion(String rulesVersion) {
            this.rulesVersion = rulesVersion;
            return this;
        }

        public RedactionReport build() {
            return new RedactionReport(
                    Collections.unmodifiableMap(new TreeMap<>(rulesApplied)),
                    Collections.unmodifiableMap(new TreeMap<>(attributesDropped)),
                    urlsNormalized.get(),
                    textsTruncated.get(),
                    payloadSha256,
                    rulesVersion
            );
        }
    }
}
