package com.company.testgen.validation;

import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.ResolvedLocator;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.Objects;

/**
 * Exact deterministic translator from ResolvedLocator strategies to Jsoup queries (§6.4, §6.6).
 * No default switch branch: unmapped strategies cause a compile-time failure.
 */
public final class JsoupLocatorTranslator {

    private JsoupLocatorTranslator() {}

    /**
     * Executes the exact Jsoup query corresponding to the ResolvedLocator.
     */
    public static Elements query(Document doc, ResolvedLocator l) {
        Objects.requireNonNull(doc, "document cannot be null");
        Objects.requireNonNull(l, "locator cannot be null");
        Objects.requireNonNull(l.strategy(), "strategy cannot be null");

        return switch (l.strategy()) {
            case TEST_ID ->
                    doc.select("[data-testid=\"%s\"]".formatted(escapeCss(l.value())));
            case PLACEHOLDER ->
                    doc.select("[placeholder=\"%s\"]".formatted(escapeCss(l.value())));
            case ROLE_NAME ->
                    byRoleAndName(doc, l.role(), l.value());
            case LABEL ->
                    byLabel(doc, l.value());
            case TEXT ->
                    byExactText(doc, l.value());
        };
        // No default branch. Adding a strategy without a mapping does not compile.
    }

    /**
     * Mirrors Playwright's getByRole: implicit-role mapping + exact accessible-name computation.
     */
    private static Elements byRoleAndName(Document doc, String role, String name) {
        Elements out = new Elements();
        if (role == null || name == null) {
            return out;
        }

        for (Element el : doc.getAllElements()) {
            if (!role.equalsIgnoreCase(AriaRoles.effectiveRole(el))) {
                continue;
            }
            String computedName = AccessibleName.compute(el);
            if (name.equals(computedName)) { // exact=true
                out.add(el);
            }
        }
        return out;
    }

    /**
     * Mirrors Playwright's getByLabel: resolves label[for], wrapping label, and aria-label.
     */
    private static Elements byLabel(Document doc, String labelText) {
        Elements out = new Elements();
        if (labelText == null || labelText.isBlank()) {
            return out;
        }

        String target = labelText.trim();

        // 1. label[for="id"]
        for (Element label : doc.select("label[for]")) {
            String txt = AccessibleName.normalize(label.text());
            if (target.equals(txt)) {
                String id = label.attr("for").trim();
                Element targetControl = doc.getElementById(id);
                if (targetControl != null && !out.contains(targetControl)) {
                    out.add(targetControl);
                }
            }
        }

        // 2. Wrapping label
        for (Element label : doc.select("label:not([for])")) {
            String txt = AccessibleName.normalize(label.text());
            if (target.equals(txt)) {
                Element control = label.selectFirst("input, textarea, select, button");
                if (control != null && !out.contains(control)) {
                    out.add(control);
                }
            }
        }

        // 3. aria-label and aria-labelledby on controls
        for (Element el : doc.select("[aria-label], [aria-labelledby]")) {
            if (target.equals(AccessibleName.compute(el)) && !out.contains(el)) {
                out.add(el);
            }
        }

        return out;
    }

    /**
     * Mirrors Playwright's getByText with exact:true, matching innermost elements with exact text.
     */
    private static Elements byExactText(Document doc, String textValue) {
        Elements out = new Elements();
        if (textValue == null || textValue.isBlank()) {
            return out;
        }

        String target = textValue.trim();
        for (Element el : doc.getAllElements()) {
            if (el.tagName().equalsIgnoreCase("body") || el.tagName().equalsIgnoreCase("html")) {
                continue;
            }

            String ownText = el.ownText().trim();
            String fullText = AccessibleName.normalize(el.text());

            boolean matches = target.equals(ownText) || target.equals(fullText);
            if (matches) {
                // Leaf-ish preference: only add if no descendant child also matches target exactly
                boolean childAlsoMatches = el.children().stream()
                        .anyMatch(child -> target.equals(AccessibleName.normalize(child.text())));
                if (!childAlsoMatches && !out.contains(el)) {
                    out.add(el);
                }
            }
        }
        return out;
    }

    private static String escapeCss(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\"", "\\\"");
    }
}
