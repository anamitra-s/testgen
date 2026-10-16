package com.company.testgen.validation;

import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.Severity;
import com.company.testgen.model.ValidationResult;
import com.company.testgen.model.ValidationViolation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * L0 Grammar Validator enforcing structural constraints, banned selector idioms,
 * ARIA role validity, and locator set completeness (§6.4, §6.5, FR-08, FR-09).
 */
public class LocatorGrammarValidator {

    private static final List<Pattern> BANNED = List.of(
            Pattern.compile(":nth-(child|of-type)"),
            Pattern.compile(">>"),
            Pattern.compile("\\.nth\\("),
            Pattern.compile("xpath=|//"),
            Pattern.compile("generated-id-\\d+"),
            Pattern.compile(":r[0-9a-z]+:"),          // React useId
            Pattern.compile("\\b(mui|ember|ng)-?\\d{2,}\\b")
    );

    public ValidationResult validate(ResolvedLocatorSet set, ParsedTestPlan plan) {
        Objects.requireNonNull(set, "locator set cannot be null");
        Objects.requireNonNull(plan, "parsed test plan cannot be null");

        List<ValidationViolation> violations = new ArrayList<>();
        Set<String> expectedRefs = plan.interactedRefIds();

        for (ResolvedLocator l : set.locators()) {
            if (l == null) {
                continue;
            }

            if (l.elementRefId() == null || !expectedRefs.contains(l.elementRefId())) {
                violations.add(blocking(l, "UNKNOWN_ELEMENT_REF", "not present in the recording"));
            }

            if (l.strategy() == null) {
                violations.add(blocking(l, "INVALID_STRATEGY", "locator strategy cannot be null"));
                continue;
            }

            boolean malformed = false;
            if (l.value() == null || l.value().isBlank()) {
                violations.add(blocking(l, "EMPTY_VALUE", null));
                malformed = true;
            }

            if (l.strategy() == LocatorStrategy.ROLE_NAME && !AriaRoles.isValid(l.role())) {
                violations.add(blocking(l, "INVALID_ROLE", l.role()));
                malformed = true;
            }

            if (malformed) {
                continue;
            }

            String expr;
            try {
                expr = LocatorRenderer.render(l);
            } catch (Exception e) {
                expr = l.strategy() + ":" + l.value();
            }

            final String renderedExpr = expr;
            BANNED.stream()
                    .filter(p -> p.matcher(renderedExpr).find() || (l.value() != null && p.matcher(l.value()).find()))
                    .findFirst()
                    .ifPresent(p -> violations.add(blocking(l, "BANNED_PATTERN", p.pattern())));

            if (l.strategy() == LocatorStrategy.TEXT) {
                violations.add(advisory(l, "LOW_CONFIDENCE_STRATEGY",
                        "TEXT locators break on copy changes and i18n"));
            }
        }

        // Completeness: every interacted element must have exactly one locator
        Map<String, Long> perRef = set.locators().stream()
                .filter(l -> l != null && l.elementRefId() != null)
                .collect(Collectors.groupingBy(ResolvedLocator::elementRefId, Collectors.counting()));

        expectedRefs.forEach(ref -> {
            long n = perRef.getOrDefault(ref, 0L);
            if (n == 0) {
                violations.add(blockingRef(ref, "MISSING_LOCATOR", null));
            } else if (n > 1) {
                violations.add(blockingRef(ref, "DUPLICATE_LOCATOR", "count=" + n));
            }
        });

        return new ValidationResult(violations);
    }

    private static ValidationViolation blocking(ResolvedLocator l, String reasonCode, String detail) {
        String expr = (l != null && l.strategy() != null) ? safeRender(l) : null;
        String ref = l != null ? l.elementRefId() : null;
        return new ValidationViolation(expr, ref, reasonCode, -1, Severity.BLOCKING, detail);
    }

    private static ValidationViolation advisory(ResolvedLocator l, String reasonCode, String detail) {
        String expr = (l != null && l.strategy() != null) ? safeRender(l) : null;
        String ref = l != null ? l.elementRefId() : null;
        return new ValidationViolation(expr, ref, reasonCode, -1, Severity.ADVISORY, detail);
    }

    private static ValidationViolation blockingRef(String refId, String reasonCode, String detail) {
        return new ValidationViolation(null, refId, reasonCode, -1, Severity.BLOCKING, detail);
    }

    private static String safeRender(ResolvedLocator l) {
        if (l == null || l.strategy() == null) {
            return null;
        }
        if (l.value() == null || l.value().isBlank()
                || (l.strategy() == LocatorStrategy.ROLE_NAME && !AriaRoles.isValid(l.role()))) {
            return l.strategy() + ":" + l.value();
        }
        try {
            return LocatorRenderer.render(l);
        } catch (Exception e) {
            return l.strategy() + ":" + l.value();
        }
    }
}
