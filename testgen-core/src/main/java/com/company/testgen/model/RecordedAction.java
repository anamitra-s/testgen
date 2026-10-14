package com.company.testgen.model;

/**
 * Recorded user action (§6.2).
 */
public record RecordedAction(
        int index,
        String type,
        String elementRefId,
        String value,
        String stateId
) {}
