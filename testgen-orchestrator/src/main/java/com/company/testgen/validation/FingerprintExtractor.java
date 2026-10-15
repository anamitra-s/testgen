package com.company.testgen.validation;

import com.company.testgen.idempotency.IdempotencyKeyDeriver;
import com.company.testgen.model.ElementFingerprint;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.Locale;
import java.util.Objects;

/**
 * Extracts structural and semantic ElementFingerprint from matched Jsoup elements (§6.2, §6.6).
 */
public final class FingerprintExtractor {

    private FingerprintExtractor() {}

    /**
     * Extracts an ElementFingerprint from a Jsoup Element within its owning Document.
     */
    public static ElementFingerprint from(Element el, Document doc) {
        Objects.requireNonNull(el, "element cannot be null");
        Objects.requireNonNull(doc, "document cannot be null");

        String tagName = el.tagName().toLowerCase(Locale.ROOT);
        String role = AriaRoles.effectiveRole(el);
        String accessibleName = AccessibleName.compute(el);
        String text = el.text();
        String normalizedText = text != null ? AccessibleName.normalize(text) : "";
        if (normalizedText != null && normalizedText.length() > 120) {
            normalizedText = normalizedText.substring(0, 120);
        }

        // Count ordinal among elements sharing the same effective role in document order
        int ordinal = 0;
        if (role != null) {
            for (Element peer : doc.getAllElements()) {
                if (peer == el) {
                    break;
                }
                if (role.equalsIgnoreCase(AriaRoles.effectiveRole(peer))) {
                    ordinal++;
                }
            }
        }

        // Compute domPathHash: sha256 of ancestor tag + ordinal chain up to body
        StringBuilder path = new StringBuilder();
        Element cur = el;
        while (cur != null && !cur.tagName().equalsIgnoreCase("body") && !cur.tagName().equalsIgnoreCase("html")) {
            path.append(cur.tagName().toLowerCase(Locale.ROOT))
                    .append("[").append(cur.elementSiblingIndex()).append("]>/");
            cur = cur.parent();
        }
        String domPathHash = "sha256:" + IdempotencyKeyDeriver.sha256Hex(path.toString());

        return new ElementFingerprint(tagName, role, accessibleName, normalizedText, ordinal, domPathHash);
    }
}
