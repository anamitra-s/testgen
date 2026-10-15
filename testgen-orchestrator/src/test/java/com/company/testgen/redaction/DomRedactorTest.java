package com.company.testgen.redaction;

import com.company.testgen.model.DomElementSnapshot;
import com.company.testgen.model.ElementFingerprint;
import com.company.testgen.model.PageSnapshot;
import com.company.testgen.model.Redacted;
import com.company.testgen.model.RedactionReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for DomRedactor (§6.9, §9.5).
 * Verifies that:
 * 1. 'value' attribute is ALWAYS dropped.
 * 2. PII is replaced with typed tokens ([REDACTED:EMAIL], [REDACTED:PHONE], [REDACTED:CARD], [REDACTED:ID]).
 * 3. Failures throw RedactionFailedException (fail-closed security stance).
 */
class DomRedactorTest {

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
    @DisplayName("DomRedactor fails closed by throwing RedactionFailedException on null input")
    void testFailClosedOnNullPageSnapshot() {
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
}
