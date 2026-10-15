package com.company.testgen.validation;

import com.company.testgen.model.DomElementSnapshot;
import com.company.testgen.model.ElementFingerprint;
import com.company.testgen.model.PageSnapshot;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.Severity;
import com.company.testgen.model.ValidationResult;
import com.company.testgen.model.ValidationViolation;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * L1 Static DOM Validator evaluating locator uniqueness and semantic identity against captured DOM (§6.6, FR-10, FR-11, FR-12).
 * Caches parsed Jsoup Documents per snapshot and supports degraded mode checks when HTML is missing.
 */
public class StaticDomValidator {

    /**
     * Cache for parsed Jsoup Documents across locator evaluations in a single validation run.
     */
    public static class JsoupCache {
        private final Map<String, Document> cache = new HashMap<>();

        public Document parse(PageSnapshot snapshot) {
            if (snapshot == null || !snapshot.hasHtml()) {
                return null;
            }
            String key = snapshot.stateId() != null && !snapshot.stateId().isBlank()
                    ? snapshot.stateId()
                    : snapshot.prunedHtml();

            return cache.computeIfAbsent(key, k -> {
                Document doc = Jsoup.parse(snapshot.prunedHtml());
                doc.outputSettings().prettyPrint(false);
                return doc;
            });
        }
    }

    public ValidationResult validate(ResolvedLocatorSet set, ParsedTestPlan plan) {
        Objects.requireNonNull(set, "locator set cannot be null");
        Objects.requireNonNull(plan, "test plan cannot be null");

        List<ValidationViolation> violations = new ArrayList<>();
        JsoupCache jsoupCache = new JsoupCache();

        for (ResolvedLocator loc : set.locators()) {
            PageSnapshot snapshot = plan.snapshotFor(loc.elementRefId());

            if (snapshot == null || !snapshot.hasHtml()) {
                violations.addAll(degradedCheck(loc, snapshot));
                continue;
            }

            Document doc = jsoupCache.parse(snapshot);
            Elements matched;
            try {
                matched = JsoupLocatorTranslator.query(doc, loc);
            } catch (Exception e) {
                violations.add(blocking(loc, "NOT_UNIQUE", 0, "Query evaluation failed: " + e.getMessage()));
                continue;
            }

            if (matched.size() != 1) {
                violations.add(blocking(loc, "NOT_UNIQUE", matched.size(),
                        matched.isEmpty()
                                ? "no element matched — the value may be wrong or the element may be dynamic"
                                : "matched %d elements: %s".formatted(matched.size(), describe(matched, 3))));
                continue;
            }

            Element matchedElement = matched.first();
            ElementFingerprint actual = FingerprintExtractor.from(matchedElement, doc);
            ElementFingerprint expected = snapshot.fingerprintFor(loc.elementRefId());

            if (expected != null && !actual.matchesIdentity(expected)) {
                violations.add(blocking(loc, "WRONG_ELEMENT", 1,
                        "expected %s but locator resolves to %s"
                                .formatted(expected.describe(), actual.describe())));
            } else if (expected != null && !Objects.equals(actual.domPathHash(), expected.domPathHash())) {
                violations.add(advisory(loc, "STRUCTURAL_DRIFT", 1,
                        "identity matches but DOM position differs — likely a benign refactor"));
            }
        }

        return new ValidationResult(violations);
    }

    private List<ValidationViolation> degradedCheck(ResolvedLocator loc, PageSnapshot snapshot) {
        List<ValidationViolation> violations = new ArrayList<>();
        violations.add(new ValidationViolation(
                safeRender(loc),
                loc.elementRefId(),
                "DEGRADED_NO_HTML",
                -1,
                Severity.ADVISORY,
                "No page HTML; identity unverifiable, operating in degraded mode"
        ));

        if (snapshot != null && snapshot.interactedElements() != null && !snapshot.interactedElements().isEmpty()) {
            long matchCount = snapshot.interactedElements().stream()
                    .filter(el -> matchesInteractedElement(loc, el))
                    .count();

            if (matchCount != 1) {
                violations.add(blocking(loc, "NOT_UNIQUE", (int) matchCount,
                        matchCount == 0
                                ? "no element matched in captured interactions"
                                : "matched %d elements in captured interactions".formatted(matchCount)));
            }
        }

        return violations;
    }

    private boolean matchesInteractedElement(ResolvedLocator loc, DomElementSnapshot el) {
        if (loc == null || el == null || loc.strategy() == null) {
            return false;
        }

        String val = loc.value();
        return switch (loc.strategy()) {
            case TEST_ID ->
                    el.attributes() != null && Objects.equals(val, el.attributes().get("data-testid"));
            case PLACEHOLDER ->
                    el.attributes() != null && Objects.equals(val, el.attributes().get("placeholder"));
            case ROLE_NAME ->
                    (loc.role() == null || loc.role().equalsIgnoreCase(el.role()))
                            && Objects.equals(val, el.accessibleName());
            case LABEL ->
                    (el.attributes() != null && Objects.equals(val, el.attributes().get("aria-label")))
                            || Objects.equals(val, el.accessibleName());
            case TEXT ->
                    Objects.equals(val, el.textContent());
        };
    }

    private static String describe(Elements elements, int max) {
        List<String> desc = new ArrayList<>();
        for (int i = 0; i < Math.min(elements.size(), max); i++) {
            Element el = elements.get(i);
            desc.add("<%s id=\"%s\" class=\"%s\">".formatted(
                    el.tagName(),
                    el.id(),
                    el.className()
            ));
        }
        if (elements.size() > max) {
            desc.add("... (and " + (elements.size() - max) + " more)");
        }
        return String.join(", ", desc);
    }

    private static ValidationViolation blocking(ResolvedLocator l, String reasonCode, int matchCount, String detail) {
        return new ValidationViolation(safeRender(l), l.elementRefId(), reasonCode, matchCount, Severity.BLOCKING, detail);
    }

    private static ValidationViolation advisory(ResolvedLocator l, String reasonCode, int matchCount, String detail) {
        return new ValidationViolation(safeRender(l), l.elementRefId(), reasonCode, matchCount, Severity.ADVISORY, detail);
    }

    private static String safeRender(ResolvedLocator l) {
        try {
            return LocatorRenderer.render(l);
        } catch (Exception e) {
            return l.strategy() + ":" + l.value();
        }
    }
}
