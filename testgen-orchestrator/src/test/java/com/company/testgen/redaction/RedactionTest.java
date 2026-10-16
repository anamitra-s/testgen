package com.company.testgen.redaction;

import com.company.testgen.model.DomElementSnapshot;
import com.company.testgen.model.ElementFingerprint;
import com.company.testgen.model.PageSnapshot;
import com.company.testgen.model.Redacted;
import com.company.testgen.model.RedactionReport;
import com.company.testgen.validation.FingerprintExtractor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedactionTest {

    @Test
    void testRedactionRulesAllowAndDeny() {
        // Allowed attributes
        assertThat(RedactionRules.isAllowed("id")).isTrue();
        assertThat(RedactionRules.isAllowed("class")).isTrue();
        assertThat(RedactionRules.isAllowed("data-testid")).isTrue();
        assertThat(RedactionRules.isAllowed("aria-label")).isTrue();
        assertThat(RedactionRules.isAllowed("aria-expanded")).isTrue();
        assertThat(RedactionRules.isAllowed("href")).isTrue();
        assertThat(RedactionRules.isAllowed("placeholder")).isTrue();

        // Strict deny-list (NEVER)
        assertThat(RedactionRules.isAllowed("value")).isFalse();
        assertThat(RedactionRules.isAllowed("VALUE")).isFalse();
        assertThat(RedactionRules.isAllowed("src")).isFalse();
        assertThat(RedactionRules.isAllowed("srcset")).isFalse();
        assertThat(RedactionRules.isAllowed("style")).isFalse();
        assertThat(RedactionRules.isAllowed("content")).isFalse();
        assertThat(RedactionRules.isAllowed("data-value")).isFalse();

        // Not in allow-list
        assertThat(RedactionRules.isAllowed("data-customer-email")).isFalse();
        assertThat(RedactionRules.isAllowed("onclick")).isFalse();
        assertThat(RedactionRules.isAllowed(null)).isFalse();
    }

    @Test
    void testPiiScrubberReplacesTypedTokens() {
        PiiScrubber scrubber = new PiiScrubber();
        RedactionReport.Builder report = RedactionReport.builder();

        // Email
        String text1 = "Contact customer at support@company.com for details";
        assertThat(scrubber.scrub(text1, report))
                .isEqualTo("Contact customer at [REDACTED:EMAIL] for details");

        // Phone
        String text2 = "Call customer at +1 555-123-4567 immediately";
        assertThat(scrubber.scrub(text2, report))
                .isEqualTo("Call customer at [REDACTED:PHONE] immediately");

        // UUID / ID
        String text3 = "Record id is 123e4567-e89b-12d3-a456-426614174000";
        assertThat(scrubber.scrub(text3, report))
                .isEqualTo("Record id is [REDACTED:ID]");

        // Card (valid 16-digit Luhn card: 4111 1111 1111 1111)
        String text4 = "Paid with card 4111 1111 1111 1111 in full";
        assertThat(scrubber.scrub(text4, report))
                .isEqualTo("Paid with card [REDACTED:CARD] in full");

        // Multiple in one string
        String text5 = "Send email to alice@corp.org or phone +91 9876543210";
        assertThat(scrubber.scrub(text5, report))
                .isEqualTo("Send email to [REDACTED:EMAIL] or phone [REDACTED:PHONE]");

        RedactionReport r = report.build();
        assertThat(r.rulesApplied().get("EMAIL")).isEqualTo(2);
        assertThat(r.rulesApplied().get("PHONE")).isEqualTo(2);
        assertThat(r.rulesApplied().get("ID")).isEqualTo(1);
        assertThat(r.rulesApplied().get("CARD")).isEqualTo(1);
    }

    @Test
    void testPiiScrubberTruncatesLongText() {
        PiiScrubber scrubber = new PiiScrubber();
        RedactionReport.Builder report = RedactionReport.builder();

        String longText = "A".repeat(150);
        String scrubbed = scrubber.scrub(longText, report);

        assertThat(scrubbed).hasSize(121); // 120 + "…"
        assertThat(scrubbed).endsWith("…");
        assertThat(report.build().textsTruncated()).isEqualTo(1);
    }

    @Test
    void testUrlNormalizerStripsQueryFragmentAndReplacesIds() {
        UrlNormalizer normalizer = new UrlNormalizer();
        RedactionReport.Builder report = RedactionReport.builder();

        // Exact example from design doc §6.9
        String raw = "https://app.example.com/customers/98213/invoices/f7a14c9e-1234-4c9e-8765-abcdef012345?token=abc&tab=2#top";
        String normalized = normalizer.normalize(raw, report);

        assertThat(normalized).isEqualTo("https://app.example.com/customers/{id}/invoices/{id}");

        // Relative path
        String relative = "/users/456/profile?refresh=true#details";
        assertThat(normalizer.normalize(relative, report)).isEqualTo("/users/{id}/profile");

        assertThat(report.build().urlsNormalized()).isEqualTo(2);
    }

    @Test
    void testDomRedactorRedactsPageSnapshotAndBuildsReport() {
        DomRedactor redactor = new DomRedactor();

        ElementFingerprint fp = new ElementFingerprint(
                "BUTTON", "button", "Submit", "Submit", 0, "hash1"
        );

        DomElementSnapshot el = new DomElementSnapshot(
                "e1",
                "button",
                Map.of(
                        "data-testid", "submit-btn",
                        "value", "super-secret-user-input",  // MUST BE DROPPED
                        "style", "color: red",               // MUST BE DROPPED
                        "aria-label", "Submit for john@test.com", // PII SCRUBBED
                        "data-user-id", "12345"              // DISALLOWED DROPPED
                ),
                "Submit form for john@test.com",
                "button",
                "Submit for john@test.com",
                fp
        );

        String html = """
                <main id="root" style="display:block">
                    <!-- Sensitive comment with password -->
                    <script>alert('secret');</script>
                    <style>.hidden { display: none; }</style>
                    <button data-testid="submit-btn" value="secret-val" onclick="doSomething()">
                        Click support@example.com
                    </button>
                    <a href="https://example.com/users/999?secret=token#frag">User Profile</a>
                </main>
                """;

        PageSnapshot raw = new PageSnapshot(
                "state-0",
                "https://app.example.com/dashboard/98213?token=xyz#overview",
                html,
                List.of(el),
                Instant.now()
        );

        Redacted<PageSnapshot> redacted = redactor.redact(raw);
        PageSnapshot safe = redacted.value();
        RedactionReport report = redacted.report();

        // 1. Verify URL is normalized
        assertThat(safe.normalizedUrl()).isEqualTo("https://app.example.com/dashboard/{id}");

        // 2. Verify element attributes were filtered & scrubbed
        DomElementSnapshot safeEl = safe.interactedElements().get(0);
        assertThat(safeEl.attributes()).containsKey("data-testid");
        assertThat(safeEl.attributes()).doesNotContainKey("value");
        assertThat(safeEl.attributes()).doesNotContainKey("style");
        assertThat(safeEl.attributes()).doesNotContainKey("data-user-id");
        assertThat(safeEl.attributes().get("aria-label"))
                .isEqualTo("Submit for [REDACTED:EMAIL]");

        // 3. Verify textContent was scrubbed
        assertThat(safeEl.textContent()).isEqualTo("Submit form for [REDACTED:EMAIL]");
        assertThat(safeEl.accessibleName()).isEqualTo("Submit for [REDACTED:EMAIL]");

        // 4. Verify HTML redactions
        String safeHtml = safe.prunedHtml();
        assertThat(safeHtml).doesNotContain("<script>");
        assertThat(safeHtml).doesNotContain("<style>");
        assertThat(safeHtml).doesNotContain("Sensitive comment");
        assertThat(safeHtml).doesNotContain("value=");
        assertThat(safeHtml).doesNotContain("onclick=");
        assertThat(safeHtml).doesNotContain("style=");
        assertThat(safeHtml).contains("Click [REDACTED:EMAIL]");
        assertThat(safeHtml).contains("href=\"https://example.com/users/{id}\"");

        // 5. Verify audit report
        assertThat(report.attributesDropped()).containsKey("value");
        assertThat(report.attributesDropped()).containsKey("style");
        assertThat(report.rulesApplied().get("EMAIL")).isGreaterThanOrEqualTo(1);
        assertThat(report.urlsNormalized()).isGreaterThanOrEqualTo(2);
        assertThat(report.payloadSha256()).startsWith("sha256:");
        assertThat(report.rulesVersion()).isEqualTo("redaction-v3");
    }

    @Test
    @DisplayName("DomRedactor always drops 'value' attribute from both HTML and DomElementSnapshot")
    void testValueAttributeAlwaysDropped() {
        DomRedactor redactor = new DomRedactor();

        Map<String, String> attrs = new HashMap<>();
        attrs.put("data-testid", "password-input");
        attrs.put("value", "MySuperSecretPassword123!");
        attrs.put("VALUE", "SecretUppercase");
        attrs.put("type", "password");
        attrs.put("placeholder", "Enter password");

        ElementFingerprint fp = new ElementFingerprint("INPUT", "textbox", "password", "Enter password", 0, "h1");
        DomElementSnapshot el = new DomElementSnapshot(
                "e1", "input", attrs, "", "textbox", "Enter password", fp
        );

        String html = """
                <div>
                    <input data-testid="username" value="admin_user_bob" type="text" />
                    <input data-testid="password-input" value="MySuperSecretPassword123!" type="password" />
                    <button data-testid="submit-btn" value="secret-btn-val">Submit</button>
                </div>
                """;

        PageSnapshot raw = new PageSnapshot(
                "s1", "https://app.example.com/login", html, List.of(el), Instant.now()
        );

        Redacted<PageSnapshot> redacted = redactor.redact(raw);
        PageSnapshot safe = redacted.value();
        RedactionReport report = redacted.report();

        // 1. Verify DomElementSnapshot has 'value' dropped
        DomElementSnapshot safeEl = safe.interactedElements().get(0);
        assertThat(safeEl.attributes()).doesNotContainKey("value");
        assertThat(safeEl.attributes()).doesNotContainKey("VALUE");
        assertThat(safeEl.attributes()).containsKey("data-testid");
        assertThat(safeEl.attributes()).containsKey("placeholder");

        // 2. Verify pruned HTML has NO value attributes
        String safeHtml = safe.prunedHtml();
        assertThat(safeHtml).doesNotContain("MySuperSecretPassword123!");
        assertThat(safeHtml).doesNotContain("admin_user_bob");
        assertThat(safeHtml).doesNotContain("secret-btn-val");
        assertThat(safeHtml).doesNotContain("value=");

        // 3. Verify audit report recorded the dropped attributes
        assertThat(report.attributesDropped()).containsKey("value");
        assertThat(report.attributesDropped().get("value")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("DomRedactor replaces PII with typed tokens in text nodes, attributes, and element snapshots")
    void testPiiReplacedWithTypedTokens() {
        DomRedactor redactor = new DomRedactor();

        Map<String, String> attrs = Map.of(
                "data-testid", "user-profile",
                "aria-label", "Profile for customer alice@example.com",
                "title", "Contact: +1 555-234-5678"
        );

        ElementFingerprint fp = new ElementFingerprint("DIV", "region", "Profile", "Profile", 0, "h2");
        DomElementSnapshot el = new DomElementSnapshot(
                "e2", "div", attrs,
                "User account 123e4567-e89b-12d3-a456-426614174000 paid with card 4111 1111 1111 1111",
                "region", "Profile for customer alice@example.com", fp
        );

        String html = """
                <div data-testid="user-profile" aria-label="Profile for customer alice@example.com">
                    <p>Email: alice@example.com</p>
                    <p>Phone: +1 555-234-5678</p>
                    <p>Order ID: 123e4567-e89b-12d3-a456-426614174000</p>
                    <p>Card: 4111 1111 1111 1111</p>
                </div>
                """;

        PageSnapshot raw = new PageSnapshot(
                "s2", "https://app.example.com/customers/99281?tab=profile", html, List.of(el), Instant.now()
        );

        Redacted<PageSnapshot> redacted = redactor.redact(raw);
        PageSnapshot safe = redacted.value();
        RedactionReport report = redacted.report();

        // Check DomElementSnapshot scrubbed
        DomElementSnapshot safeEl = safe.interactedElements().get(0);
        assertThat(safeEl.attributes().get("aria-label"))
                .isEqualTo("Profile for customer [REDACTED:EMAIL]");
        assertThat(safeEl.accessibleName())
                .isEqualTo("Profile for customer [REDACTED:EMAIL]");
        assertThat(safeEl.textContent())
                .contains("[REDACTED:ID]")
                .contains("[REDACTED:CARD]")
                .doesNotContain("123e4567-e89b-12d3-a456-426614174000")
                .doesNotContain("4111 1111 1111 1111");

        // Check HTML content scrubbed
        String safeHtml = safe.prunedHtml();
        assertThat(safeHtml)
                .contains("[REDACTED:EMAIL]")
                .contains("[REDACTED:PHONE]")
                .contains("[REDACTED:ID]")
                .contains("[REDACTED:CARD]")
                .doesNotContain("alice@example.com")
                .doesNotContain("+1 555-234-5678")
                .doesNotContain("123e4567-e89b-12d3-a456-426614174000")
                .doesNotContain("4111 1111 1111 1111");

        // Audit report verification
        assertThat(report.rulesApplied()).containsKeys("EMAIL", "PHONE", "ID", "CARD");
        assertThat(report.rulesApplied().get("EMAIL")).isGreaterThanOrEqualTo(2);
        assertThat(report.rulesApplied().get("PHONE")).isGreaterThanOrEqualTo(1);
        assertThat(report.rulesApplied().get("ID")).isGreaterThanOrEqualTo(1);
        assertThat(report.rulesApplied().get("CARD")).isGreaterThanOrEqualTo(1);
        assertThat(report.payloadSha256()).startsWith("sha256:");
    }

    @Test
    @DisplayName("DomRedactor scrubs PII from ElementFingerprint and preserves identity match with redacted DOM")
    void testFingerprintPiiScrubbedAndPreservesIdentity() {
        DomRedactor redactor = new DomRedactor();

        ElementFingerprint rawFp = new ElementFingerprint(
                "button", "button",
                "Continue as jane.doe@company.com",
                "Continue as jane.doe@company.com",
                0, "sha256:path123"
        );

        DomElementSnapshot el = new DomElementSnapshot(
                "e1", "button", Map.of("data-testid", "submit-btn"),
                "Continue as jane.doe@company.com",
                "button",
                "Continue as jane.doe@company.com",
                rawFp
        );

        String html = """
                <html><body><button data-testid="submit-btn">Continue as jane.doe@company.com</button></body></html>
                """;

        PageSnapshot raw = new PageSnapshot(
                "s1", "https://app.example.com", html, List.of(el), Instant.now()
        );

        Redacted<PageSnapshot> redacted = redactor.redact(raw);
        PageSnapshot safe = redacted.value();

        DomElementSnapshot safeEl = safe.interactedElements().get(0);
        ElementFingerprint safeFp = safeEl.fingerprint();

        // 1. Verify safe fingerprint contains scrubbed PII
        assertThat(safeFp).isNotNull();
        assertThat(safeFp.accessibleName()).isEqualTo("Continue as [REDACTED:EMAIL]");
        assertThat(safeFp.normalizedText()).isEqualTo("Continue as [REDACTED:EMAIL]");
        assertThat(safeFp.accessibleName()).doesNotContain("jane.doe@company.com");
        assertThat(safeFp.normalizedText()).doesNotContain("jane.doe@company.com");

        // 2. Verify identity match with fingerprint extracted from redacted HTML
        Document doc = Jsoup.parse(safe.prunedHtml());
        ElementFingerprint extractedFp = FingerprintExtractor.from(
                doc.selectFirst("[data-testid=\"submit-btn\"]"), doc
        );

        assertThat(extractedFp.matchesIdentity(safeFp)).isTrue();
    }

    @Test
    @DisplayName("DomRedactor strips dangerous elements and comments from HTML")
    void testDangerousTagsAndCommentsStripped() {
        DomRedactor redactor = new DomRedactor();

        String html = """
                <div>
                    <!-- DB password: secret-db-pass -->
                    <script>evilScript();</script>
                    <style>.secret { color: pink; }</style>
                    <noscript>Secret noscript</noscript>
                    <svg><text>Secret SVG text</text></svg>
                    <iframe src="https://evil.com"></iframe>
                    <template><p>Secret template</p></template>
                    <p>Clean paragraph</p>
                </div>
                """;

        PageSnapshot raw = new PageSnapshot(
                "s4", "https://app.example.com/view", html, List.of(), Instant.now()
        );

        Redacted<PageSnapshot> redacted = redactor.redact(raw);
        String safeHtml = redacted.value().prunedHtml();

        assertThat(safeHtml).doesNotContain("secret-db-pass");
        assertThat(safeHtml).doesNotContain("<script");
        assertThat(safeHtml).doesNotContain("<style");
        assertThat(safeHtml).doesNotContain("<noscript");
        assertThat(safeHtml).doesNotContain("<svg");
        assertThat(safeHtml).doesNotContain("<iframe");
        assertThat(safeHtml).doesNotContain("<template");
        assertThat(safeHtml).contains("<p>Clean paragraph</p>");
    }

    @Test
    @DisplayName("DomRedactor fails closed on null or error")
    void testDomRedactorFailsClosedOnNullOrError() {
        DomRedactor redactor = new DomRedactor();

        assertThatThrownBy(() -> redactor.redact(null))
                .isInstanceOf(RedactionFailedException.class)
                .hasMessageContaining("Cannot redact null PageSnapshot");
    }

    @Test
    @DisplayName("DomRedactor fails closed by throwing RedactionFailedException when internal component throws")
    void testFailClosedOnInternalException() {
        PiiScrubber faultyPii = new PiiScrubber() {
            @Override
            public String scrub(String text, RedactionReport.Builder r) {
                throw new RuntimeException("Simulated catastrophic regex/parser error");
            }
        };

        DomRedactor redactor = new DomRedactor(faultyPii, new UrlNormalizer());

        PageSnapshot raw = new PageSnapshot(
                "s3", "https://app.example.com/test", "<p>Sensitive text</p>", List.of(), Instant.now()
        );

        assertThatThrownBy(() -> redactor.redact(raw))
                .isInstanceOf(RedactionFailedException.class)
                .hasMessageContaining("Redaction failed; job aborted before any external call")
                .hasCauseInstanceOf(RuntimeException.class);
    }
}
