package com.company.testgen.cli.validation;

/**
 * Summary report returned after successful contract validation (§6.3).
 */
public record ValidationReport(
        int contractVersion,
        int actionCount,
        int interactionCount,
        boolean hasHtml,
        String targetArea,
        String baseUrl
) {}
