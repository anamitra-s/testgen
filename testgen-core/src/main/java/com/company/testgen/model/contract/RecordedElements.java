package com.company.testgen.model.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * Top-level payload for {@code recorded-elements.json} conforming to ingestion contract v2 (§6.3).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecordedElements(
        int contractVersion,
        String recorderVersion,
        Instant capturedAt,
        String targetArea,
        String baseUrl,
        List<SnapshotPayload> snapshots,
        List<InteractionPayload> interactions
) {
    public RecordedElements {
        if (snapshots == null) {
            snapshots = List.of();
        }
        if (interactions == null) {
            interactions = List.of();
        }
    }
}
