package com.company.testgen.validation;

import com.company.testgen.model.DomElementSnapshot;
import com.company.testgen.model.ElementFingerprint;
import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.PageSnapshot;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.RecordedAction;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.ValidationResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StaticDomValidatorTest {

    @Test
    @DisplayName("L1 passes when locator matches exactly one element with matching identity fingerprint")
    void testPassesExactMatch() {
        String html = "<html><body><main id=\"main\"><button data-testid=\"submit-btn\">Save</button></main></body></html>";
        Document doc = Jsoup.parse(html);
        ElementFingerprint fp = FingerprintExtractor.from(doc.selectFirst("[data-testid=\"submit-btn\"]"), doc);

        DomElementSnapshot elem = new DomElementSnapshot(
                "e1", "button", Map.of("data-testid", "submit-btn"), "Save", "button", "Save", fp
        );
        PageSnapshot snap = new PageSnapshot("s1", "https://app.example.com", html, List.of(elem), Instant.now(), false);

        ParsedTestPlan plan = new ParsedTestPlan(
                "billing", "desc", List.of(new RecordedAction(0, "click", "e1", null, "s1")),
                List.of(snap), false, false
        );

        ResolvedLocatorSet set = ResolvedLocatorSet.of(
                new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "submit-btn")
        );

        StaticDomValidator validator = new StaticDomValidator();
        ValidationResult result = validator.validate(set, plan);

        assertThat(result.passed()).isTrue();
        assertThat(result.blocking()).isEmpty();
        assertThat(result.advisory()).isEmpty();
    }

    @Test
    @DisplayName("L1 flags NOT_UNIQUE when locator matches 0 or multiple elements")
    void testNotUniqueViolations() {
        String html = """
                <html><body>
                    <button class="action">Action 1</button>
                    <button class="action">Action 2</button>
                </body></html>
                """;
        PageSnapshot snap = new PageSnapshot("s1", "https://app.example.com", html, List.of(), Instant.now(), false);

        ParsedTestPlan plan = new ParsedTestPlan(
                "billing", "desc",
                List.of(
                        new RecordedAction(0, "click", "e1", null, "s1"),
                        new RecordedAction(1, "click", "e2", null, "s1")
                ),
                List.of(snap), false, false
        );

        ResolvedLocatorSet set = ResolvedLocatorSet.of(
                new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "non-existent"), // 0 matches
                new ResolvedLocator("e2", LocatorStrategy.TEXT, "Action")          // 0 matches (since text is Action 1 / Action 2)
        );

        StaticDomValidator validator = new StaticDomValidator();
        ValidationResult result = validator.validate(set, plan);

        assertThat(result.passed()).isFalse();
        assertThat(result.blocking()).hasSize(2);
        assertThat(result.blocking()).allMatch(v -> "NOT_UNIQUE".equals(v.reasonCode()));
    }

    @Test
    @DisplayName("L1 flags WRONG_ELEMENT when matched element identity does not match recorded fingerprint")
    void testWrongElementViolation() {
        String html = "<html><body><button data-testid=\"btn\">Different Name</button></body></html>";
        PageSnapshot snap = new PageSnapshot("s1", "https://app.example.com", html, List.of(
                new DomElementSnapshot("e1", "button", Map.of(), "Expected Name", "button", "Expected Name",
                        new ElementFingerprint("button", "button", "Expected Name", "Expected Name", 0, "sha256:123"))
        ), Instant.now(), false);

        ParsedTestPlan plan = new ParsedTestPlan(
                "billing", "desc", List.of(new RecordedAction(0, "click", "e1", null, "s1")),
                List.of(snap), false, false
        );

        ResolvedLocatorSet set = ResolvedLocatorSet.of(
                new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "btn")
        );

        StaticDomValidator validator = new StaticDomValidator();
        ValidationResult result = validator.validate(set, plan);

        assertThat(result.passed()).isFalse();
        assertThat(result.blocking()).hasSize(1);
        assertThat(result.blocking().get(0).reasonCode()).isEqualTo("WRONG_ELEMENT");
    }

    @Test
    @DisplayName("L1 flags STRUCTURAL_DRIFT as advisory when identity matches but domPathHash differs")
    void testStructuralDriftAdvisory() {
        String html = "<html><body><section><div id=\"wrapper\"><button data-testid=\"btn\">Click</button></div></section></body></html>";
        Document doc = Jsoup.parse(html);
        ElementFingerprint actual = FingerprintExtractor.from(doc.selectFirst("[data-testid=\"btn\"]"), doc);

        // Expected fingerprint has the exact same semantic identity, but different domPathHash (e.g. moved inside section)
        ElementFingerprint expectedWithDifferentPath = new ElementFingerprint(
                actual.tagName(), actual.role(), actual.accessibleName(), actual.normalizedText(),
                actual.ordinalAmongRolePeers(), "sha256:prior-structural-path"
        );

        PageSnapshot snap = new PageSnapshot("s1", "https://app.example.com", html, List.of(
                new DomElementSnapshot("e1", "button", Map.of(), "Click", "button", "Click", expectedWithDifferentPath)
        ), Instant.now(), false);

        ParsedTestPlan plan = new ParsedTestPlan(
                "billing", "desc", List.of(new RecordedAction(0, "click", "e1", null, "s1")),
                List.of(snap), false, false
        );

        ResolvedLocatorSet set = ResolvedLocatorSet.of(
                new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "btn")
        );

        StaticDomValidator validator = new StaticDomValidator();
        ValidationResult result = validator.validate(set, plan);

        assertThat(result.passed()).isTrue(); // Advisory does not block!
        assertThat(result.advisory()).hasSize(1);
        assertThat(result.advisory().get(0).reasonCode()).isEqualTo("STRUCTURAL_DRIFT");
    }

    @Test
    @DisplayName("L1 handles degraded mode when snapshot has no HTML")
    void testDegradedModeWhenNoHtml() {
        DomElementSnapshot elem = new DomElementSnapshot(
                "e1", "button", Map.of("data-testid", "save-btn"), "Save", "button", "Save", null
        );
        PageSnapshot degradedSnap = new PageSnapshot("s1", "https://app.example.com", null, List.of(elem), Instant.now(), false);

        ParsedTestPlan plan = new ParsedTestPlan(
                "billing", "desc", List.of(new RecordedAction(0, "click", "e1", null, "s1")),
                List.of(degradedSnap), false, false
        );

        ResolvedLocatorSet set = ResolvedLocatorSet.of(
                new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "save-btn")
        );

        StaticDomValidator validator = new StaticDomValidator();
        ValidationResult result = validator.validate(set, plan);

        assertThat(result.passed()).isTrue();
        assertThat(result.advisory()).anyMatch(v -> "DEGRADED_NO_HTML".equals(v.reasonCode()));
    }
}
