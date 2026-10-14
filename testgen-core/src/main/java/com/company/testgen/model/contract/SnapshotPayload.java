package com.company.testgen.model.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Snapshot object as captured in contract v2 {@code recorded-elements.json} (§6.3).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SnapshotPayload(
        String stateId,
        String url,
        String prunedHtml,
        Integer prunedNodeCount,
        Boolean truncated
) {}
