package com.company.testgen.validation;

import org.jsoup.nodes.Element;

import java.util.Locale;
import java.util.Set;

/**
 * ARIA roles reference and implicit role mapping for Jsoup elements (§6.4, §6.6).
 */
public final class AriaRoles {

    private static final Set<String> KNOWN_ROLES = Set.of(
            "alert", "alertdialog", "application", "article", "banner", "button",
            "cell", "checkbox", "columnheader", "combobox", "complementary", "contentinfo",
            "definition", "dialog", "directory", "document", "feed", "figure", "form",
            "grid", "gridcell", "group", "heading", "img", "link", "list", "listbox",
            "listitem", "log", "main", "marquee", "math", "menu", "menubar", "menuitem",
            "menuitemcheckbox", "menuitemradio", "navigation", "none", "note", "option",
            "presentation", "progressbar", "radio", "radiogroup", "region", "row",
            "rowgroup", "rowheader", "scrollbar", "search", "searchbox", "separator",
            "slider", "spinbutton", "status", "switch", "tab", "table", "tablist",
            "tabpanel", "term", "textbox", "timer", "toolbar", "tooltip", "tree",
            "treegrid", "treeitem"
    );

    private AriaRoles() {}

    /**
     * Checks if a role name is a recognized ARIA role.
     */
    public static boolean isValid(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        return KNOWN_ROLES.contains(role.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Computes the effective ARIA role for a Jsoup Element using explicit role or HTML5 defaults.
     */
    public static String effectiveRole(Element el) {
        if (el == null) {
            return null;
        }

        // 1. Explicit role attribute takes precedence
        if (el.hasAttr("role")) {
            String explicit = el.attr("role").trim().toLowerCase(Locale.ROOT);
            if (!explicit.isEmpty()) {
                return explicit;
            }
        }

        // 2. Implicit role based on tag and attributes
        String tag = el.tagName().toLowerCase(Locale.ROOT);
        return switch (tag) {
            case "button" -> "button";
            case "a" -> el.hasAttr("href") ? "link" : null;
            case "input" -> {
                String type = el.attr("type").toLowerCase(Locale.ROOT);
                yield switch (type) {
                    case "button", "submit", "reset", "image" -> "button";
                    case "checkbox" -> "checkbox";
                    case "radio" -> "radio";
                    case "search" -> "searchbox";
                    case "range" -> "slider";
                    case "number" -> "spinbutton";
                    case "hidden" -> null;
                    default -> "textbox"; // text, email, password, tel, url, or omitted
                };
            }
            case "textarea" -> "textbox";
            case "select" -> "combobox";
            case "h1", "h2", "h3", "h4", "h5", "h6" -> "heading";
            case "dialog" -> "dialog";
            case "img" -> "img";
            case "ul", "ol" -> "list";
            case "li" -> "listitem";
            case "table" -> "table";
            case "tr" -> "row";
            case "th" -> "row".equalsIgnoreCase(el.attr("scope")) ? "rowheader" : "columnheader";
            case "td" -> "cell";
            case "nav" -> "navigation";
            case "main" -> "main";
            case "header" -> "banner";
            case "footer" -> "contentinfo";
            case "form" -> "form";
            case "hr" -> "separator";
            default -> null;
        };
    }
}
