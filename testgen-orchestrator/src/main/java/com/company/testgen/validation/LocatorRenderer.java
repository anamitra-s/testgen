package com.company.testgen.validation;

import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.ResolvedLocator;

import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic renderer converting ResolvedLocator domain objects into Playwright-Java expressions (§6.4).
 * Employs an exhaustive switch over LocatorStrategy with no default branch.
 */
public final class LocatorRenderer {

    private LocatorRenderer() {}

    /**
     * Renders a Playwright Java locator expression for the given resolved locator.
     */
    public static String render(ResolvedLocator l) {
        Objects.requireNonNull(l, "locator cannot be null");
        Objects.requireNonNull(l.strategy(), "locator strategy cannot be null");

        return switch (l.strategy()) {
            case TEST_ID ->
                    "page.getByTestId(%s)".formatted(q(l.value()));
            case ROLE_NAME -> {
                String role = (l.role() != null && !l.role().isBlank())
                        ? l.role().trim().toUpperCase(Locale.ROOT)
                        : "UNKNOWN";
                yield "page.getByRole(AriaRole.%s, new Page.GetByRoleOptions().setName(%s).setExact(true))"
                        .formatted(role, q(l.value()));
            }
            case LABEL ->
                    "page.getByLabel(%s, new Page.GetByLabelOptions().setExact(true))".formatted(q(l.value()));
            case PLACEHOLDER ->
                    "page.getByPlaceholder(%s, new Page.GetByPlaceholderOptions().setExact(true))".formatted(q(l.value()));
            case TEXT ->
                    "page.getByText(%s, new Page.GetByTextOptions().setExact(true))".formatted(q(l.value()));
        };
        // No default branch. A new strategy is a COMPILE ERROR, not a silent fallthrough.
    }

    private static String q(String s) {
        if (s == null) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
