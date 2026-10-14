package com.company.testgen.redaction;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Attribute allow-list and strict deny-list rules for HTML redaction (§6.9).
 * <p>
 * An allow-list is mandatory: a deny-list loses by default because a custom attribute
 * such as {@code data-customer-email} defeats it.
 * {@code value} attributes are dropped unconditionally.
 */
public final class RedactionRules {

    /**
     * ALLOW-LIST of permitted attributes.
     */
    public static final Set<String> ALLOWED_ATTRS = Set.of(
            "id", "class", "name", "type", "role",
            "data-testid", "data-test", "data-test-id",
            "alt", "title", "placeholder", "href", "for", "disabled", "checked", "selected"
    );

    /**
     * Prefix matcher for allowed dynamic attributes (all {@code aria-*} attributes).
     */
    public static final Predicate<String> ALLOWED_PREFIX = a -> a.startsWith("aria-");

    /**
     * ALWAYS DROPPED unconditionally, even if they would otherwise match the allow-list.
     */
    public static final Set<String> NEVER = Set.of(
            "value", "src", "srcset", "style", "content", "data-value"
    );

    /**
     * Maximum character length allowed for text nodes / values before truncation.
     */
    public static final int MAX_TEXT_LEN = 120;

    /**
     * Rules version identifier stored in audit reports.
     */
    public static final String RULES_VERSION = "redaction-v3";

    private RedactionRules() {}

    /**
     * Determines whether an attribute is permitted according to allow-list and deny-list rules.
     *
     * @param attributeName the HTML attribute name
     * @return true if permitted, false if it must be dropped
     */
    public static boolean isAllowed(String attributeName) {
        if (attributeName == null) {
            return false;
        }
        String k = attributeName.toLowerCase(Locale.ROOT);
        return !NEVER.contains(k) && (ALLOWED_ATTRS.contains(k) || ALLOWED_PREFIX.test(k));
    }
}
