package com.company.testgen.validation;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative accessible name computation for Jsoup elements (§6.4, §6.6).
 * Follows the W3C Accessible Name and Description Computation specification.
 * Fails closed/null on ambiguous references to bias toward NOT_UNIQUE rather than silent false pass.
 */
public final class AccessibleName {

    private AccessibleName() {}

    /**
     * Computes the exact accessible name of the given element.
     * Returns null if unnamable or referenced elements are missing.
     */
    public static String compute(Element el) {
        if (el == null) {
            return null;
        }

        Document doc = el.ownerDocument();

        // 1. aria-labelledby takes highest priority
        if (el.hasAttr("aria-labelledby")) {
            String labelledBy = el.attr("aria-labelledby").trim();
            if (!labelledBy.isEmpty()) {
                if (doc == null) {
                    return null;
                }
                String[] ids = labelledBy.split("\\s+");
                List<String> parts = new ArrayList<>();
                for (String id : ids) {
                    Element ref = doc.getElementById(id);
                    if (ref == null) {
                        // Conservative: missing target id -> cannot reliably compute name
                        return null;
                    }
                    String text = computeTextOnly(ref);
                    if (text != null && !text.isBlank()) {
                        parts.add(text);
                    }
                }
                return normalize(String.join(" ", parts));
            }
        }

        // 2. aria-label
        if (el.hasAttr("aria-label")) {
            return normalize(el.attr("aria-label"));
        }

        // 3. Native labels for form elements
        String tag = el.tagName().toLowerCase();
        if (tag.matches("input|textarea|select|button") && doc != null) {
            if (el.hasAttr("id") && !el.attr("id").isBlank()) {
                Element label = doc.selectFirst("label[for=\"" + el.attr("id") + "\"]");
                if (label != null) {
                    return normalize(computeTextOnly(label));
                }
            }
            // Enclosing label
            Element parent = el.parent();
            while (parent != null) {
                if ("label".equalsIgnoreCase(parent.tagName())) {
                    return normalize(computeTextOnly(parent));
                }
                parent = parent.parent();
            }
        }

        // 4. Native attributes by tag
        if ("img".equalsIgnoreCase(tag) && el.hasAttr("alt")) {
            return normalize(el.attr("alt"));
        }

        if ("input".equalsIgnoreCase(tag)) {
            String type = el.attr("type").toLowerCase();
            if (type.matches("button|submit|reset") && el.hasAttr("value")) {
                return normalize(el.attr("value"));
            }
            if (el.hasAttr("placeholder")) {
                return normalize(el.attr("placeholder"));
            }
        }

        // 5. Descendant text content for standard elements (button, a, headings, etc.)
        return normalize(computeTextOnly(el));
    }

    private static String computeTextOnly(Element el) {
        if (el == null) {
            return "";
        }
        return el.text();
    }

    public static String normalize(String text) {
        if (text == null) {
            return null;
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }
}
