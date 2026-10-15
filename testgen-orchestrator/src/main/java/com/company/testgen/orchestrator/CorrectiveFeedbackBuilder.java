package com.company.testgen.orchestrator;

import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.ValidationViolation;
import com.company.testgen.validation.LocatorRenderer;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds scoped corrective feedback for the self-healing retry loop (§6.6, §6.13, FR-13, FR-14).
 * Details failing locators with reason codes, match counts, and error details,
 * and explicitly lists frozen passing locators to instruct the model not to mutate them.
 */
public class CorrectiveFeedbackBuilder {

    /**
     * Constructs formatted corrective feedback from blocking violations and the current locator set.
     *
     * @param blocking list of blocking violations from L0/L1
     * @param current current resolved locator set containing passing locators to freeze
     * @return prompt feedback string
     */
    public String build(List<ValidationViolation> blocking, ResolvedLocatorSet current) {
        StringBuilder sb = new StringBuilder("""
            The following locators FAILED deterministic validation and MUST be revised.
            Propose new locators for ONLY these elements. Do not modify any other locator;
            changes to passing locators will be discarded.

            """);

        if (blocking != null) {
            for (ValidationViolation v : blocking) {
                String expr = v.locatorExpression() != null ? v.locatorExpression() : "unrendered";
                sb.append("- element %s (%s): %s".formatted(
                        v.elementRefId() != null ? v.elementRefId() : "unknown",
                        expr,
                        v.reasonCode()
                ));
                if (v.matchCount() >= 0) {
                    sb.append(" — matched %d elements; must match exactly 1".formatted(v.matchCount()));
                }
                if (v.detail() != null && !v.detail().isBlank()) {
                    sb.append("\n    ").append(v.detail().strip());
                }
                sb.append('\n');
            }
        }

        sb.append("\nAlready-passing locators (frozen, for context only):\n");
        if (current != null && current.locators() != null) {
            Set<String> failingRefs = (blocking != null)
                    ? blocking.stream()
                        .map(ValidationViolation::elementRefId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet())
                    : Set.of();

            List<ResolvedLocator> passing = current.locators().stream()
                    .filter(l -> l.elementRefId() != null && !failingRefs.contains(l.elementRefId()))
                    .toList();

            if (passing.isEmpty()) {
                sb.append("  (none)\n");
            } else {
                for (ResolvedLocator l : passing) {
                    String expr = l.expression() != null ? l.expression() : safeRender(l);
                    sb.append("  %s -> %s\n".formatted(l.elementRefId(), expr));
                }
            }
        } else {
            sb.append("  (none)\n");
        }

        return sb.toString();
    }

    private String safeRender(ResolvedLocator l) {
        try {
            return LocatorRenderer.render(l);
        } catch (Exception e) {
            return l.strategy() + "(\"" + l.value() + "\")";
        }
    }
}
