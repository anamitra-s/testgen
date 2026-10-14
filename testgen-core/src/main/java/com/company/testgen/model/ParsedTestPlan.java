package com.company.testgen.model;

import java.util.List;

/**
 * Parsed test plan domain model (§6.2).
 */
public record ParsedTestPlan(
        String targetArea,
        String testDescription,
        List<RecordedAction> actions,
        List<PageSnapshot> snapshots,
        boolean containsLoginFlow,
        boolean mutatesData
) {}
