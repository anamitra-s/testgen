package com.company.testgen.redaction;

import com.company.testgen.model.DomElementSnapshot;
import com.company.testgen.model.ElementFingerprint;
import com.company.testgen.model.PageSnapshot;
import com.company.testgen.model.Redacted;
import com.company.testgen.model.RedactionReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
    void testDomRedactorFailsClosedOnNullOrError() {
        DomRedactor redactor = new DomRedactor();

        assertThatThrownBy(() -> redactor.redact(null))
                .isInstanceOf(RedactionFailedException.class)
                .hasMessageContaining("Cannot redact null PageSnapshot");
    }
}
