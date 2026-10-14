package com.company.testgen.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DomainModelTest {

    @Test
    void testLocatorStrategyEnumValues() {
        assertThat(LocatorStrategy.values())
                .containsExactly(
                        LocatorStrategy.TEST_ID,
                        LocatorStrategy.ROLE_NAME,
                        LocatorStrategy.LABEL,
                        LocatorStrategy.PLACEHOLDER,
                        LocatorStrategy.TEXT
                );
    }

    @Test
    void testElementFingerprintIdentityAndDescribe() {
        ElementFingerprint fp1 = new ElementFingerprint(
                "BUTTON",
                "button",
                "Submit",
                "Submit",
                0,
                "abc123hash"
        );

        ElementFingerprint fp2 = new ElementFingerprint(
                "button", // case-insensitive tag match
                "button",
                "Submit",
                "Submit now", // different normalized text
                1, // different ordinal
                "diffhash" // structural drift
        );

        ElementFingerprint fp3 = new ElementFingerprint(
                "BUTTON",
                "button",
                "Cancel",
                "Cancel",
                0,
                "abc123hash"
        );

        assertThat(fp1.matchesIdentity(fp2)).isTrue();
        assertThat(fp1.matchesIdentity(fp3)).isFalse();
        assertThat(fp1.matchesIdentity(null)).isFalse();
        assertThat(fp1.describe()).isEqualTo("BUTTON[role=button, name=Submit]");
    }

    @Test
    void testPageSnapshotAndFingerprintLookup() {
        ElementFingerprint fp = new ElementFingerprint(
                "INPUT",
                "textbox",
                "Username",
                "",
                0,
                "hash1"
        );

        DomElementSnapshot elSnapshot = new DomElementSnapshot(
                "elem-1",
                "input",
                Map.of("data-testid", "username-input"),
                "",
                "textbox",
                "Username",
                fp
        );

        PageSnapshot pageSnapshot = new PageSnapshot(
                "state-login",
                "https://example.com/login",
                "<html><body><input data-testid='username-input'/></body></html>",
                List.of(elSnapshot),
                Instant.now()
        );

        assertThat(pageSnapshot.hasHtml()).isTrue();
        assertThat(pageSnapshot.fingerprintFor("elem-1")).isEqualTo(fp);
        assertThat(pageSnapshot.fingerprintFor("unknown-elem")).isNull();

        PageSnapshot degradedSnapshot = new PageSnapshot(
                "state-degraded",
                "https://example.com/login",
                null,
                List.of(elSnapshot),
                Instant.now()
        );
        assertThat(degradedSnapshot.hasHtml()).isFalse();
    }

    @Test
    void testParsedTestPlanStructure() {
        RecordedAction action = new RecordedAction(
                0,
                "click",
                "elem-1",
                null,
                "state-login"
        );

        ElementFingerprint fp = new ElementFingerprint("BUTTON", "button", "Login", "Login", 0, "hash");
        DomElementSnapshot elSnapshot = new DomElementSnapshot(
                "elem-1", "button", Map.of(), "Login", "button", "Login", fp
        );
        PageSnapshot pageSnapshot = new PageSnapshot(
                "state-login", "https://example.com/login", "<html></html>", List.of(elSnapshot), Instant.now()
        );

        ParsedTestPlan plan = new ParsedTestPlan(
                "dashboard",
                "User logs in and views dashboard",
                List.of(action),
                List.of(pageSnapshot),
                true,
                false
        );

        assertThat(plan.targetArea()).isEqualTo("dashboard");
        assertThat(plan.testDescription()).isEqualTo("User logs in and views dashboard");
        assertThat(plan.actions()).hasSize(1);
        assertThat(plan.snapshots()).hasSize(1);
        assertThat(plan.containsLoginFlow()).isTrue();
        assertThat(plan.mutatesData()).isFalse();
    }
}
