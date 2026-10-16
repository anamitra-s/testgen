package com.company.testgen.eval;

import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.contract.RecordedElements;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Encapsulates a golden corpus evaluation case (§8.2).
 * Contains frozen test recordings, human-approved expected locators, and frozen code context.
 */
public record EvalCase(
        String caseId,
        String targetArea,
        String description,
        String sourcedFrom,
        Instant capturedAt,
        String difficulty,
        List<String> tags,
        String knownLimitations,
        String status,
        boolean expectedToEscalate,
        String recordedTestJs,
        RecordedElements recordedElements,
        Map<String, ResolvedLocator> expectedLocators,
        ExistingCodeContext existingCodeContext,
        Path caseDir
) {
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_QUARANTINED = "quarantined";
    public static final String STATUS_RETIRED = "retired";

    public EvalCase {
        Objects.requireNonNull(caseId, "caseId cannot be null");
        targetArea = targetArea != null ? targetArea : "default";
        description = description != null ? description : "";
        sourcedFrom = sourcedFrom != null ? sourcedFrom : "";
        difficulty = difficulty != null ? difficulty.toLowerCase() : "medium";
        tags = tags != null ? List.copyOf(tags) : List.of();
        knownLimitations = knownLimitations != null ? knownLimitations : "";
        status = status != null ? status.toLowerCase() : STATUS_ACTIVE;
        expectedLocators = expectedLocators != null ? Collections.unmodifiableMap(expectedLocators) : Collections.emptyMap();
        existingCodeContext = existingCodeContext != null ? existingCodeContext : ExistingCodeContext.empty();
    }

    public EvalCase(
            String caseId,
            String targetArea,
            String description,
            String sourcedFrom,
            Instant capturedAt,
            String difficulty,
            List<String> tags,
            String knownLimitations,
            String status,
            String recordedTestJs,
            RecordedElements recordedElements,
            Map<String, ResolvedLocator> expectedLocators,
            ExistingCodeContext existingCodeContext,
            Path caseDir
    ) {
        this(
                caseId, targetArea, description, sourcedFrom, capturedAt,
                difficulty, tags, knownLimitations, status, false,
                recordedTestJs, recordedElements, expectedLocators,
                existingCodeContext, caseDir
        );
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }

    public boolean isQuarantined() {
        return STATUS_QUARANTINED.equalsIgnoreCase(status);
    }

    public boolean isRetired() {
        return STATUS_RETIRED.equalsIgnoreCase(status);
    }

    public EvalCase withStatus(String newStatus) {
        return new EvalCase(
                caseId, targetArea, description, sourcedFrom, capturedAt,
                difficulty, tags, knownLimitations, newStatus, expectedToEscalate,
                recordedTestJs, recordedElements, expectedLocators,
                existingCodeContext, caseDir
        );
    }
}
