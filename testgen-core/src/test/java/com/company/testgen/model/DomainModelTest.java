package com.company.testgen.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Test
    void testRecordedInteraction() {
        ElementFingerprint fp = new ElementFingerprint("BUTTON", "button", "Save", "Save", 0, "h");
        RecordedInteraction interaction = new RecordedInteraction(
                "e1", "s0", 1, "button", Map.of("data-testid", "save-btn"), "Save", "button", "Save", fp
        );

        assertThat(interaction.elementRefId()).isEqualTo("e1");
        assertThat(interaction.stateId()).isEqualTo("s0");
        assertThat(interaction.attributes()).containsEntry("data-testid", "save-btn");
        assertThat(interaction.fingerprint()).isEqualTo(fp);

        DomElementSnapshot snapshot = new DomElementSnapshot(
                "e2", "a", Map.of("href", "/home"), "Home", "link", "Home", fp
        );
        RecordedInteraction fromSnap = RecordedInteraction.fromSnapshot("s1", 2, snapshot);
        assertThat(fromSnap.elementRefId()).isEqualTo("e2");
        assertThat(fromSnap.stateId()).isEqualTo("s1");
        assertThat(fromSnap.role()).isEqualTo("link");
    }

    @Test
    void testResolvedLocatorSetMergeKeepingAcceptedAndRetainingPassing() {
        ResolvedLocator loc1 = new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "save-btn", null, "page.getByTestId(\"save-btn\")");
        ResolvedLocator loc2 = new ResolvedLocator("e2", LocatorStrategy.ROLE_NAME, "Submit", "button", "page.getByRole(AriaRole.BUTTON, ...)");
        ResolvedLocator loc3 = new ResolvedLocator("e3", LocatorStrategy.TEXT, "Cancel", null, "page.getByText(\"Cancel\")");

        // Initial accepted has loc1
        ResolvedLocatorSet accepted = ResolvedLocatorSet.of(loc1);
        assertThat(accepted.size()).isEqualTo(1);
        assertThat(accepted.findByElementRefId("e1")).contains(loc1);

        // Proposed on retry changes loc1 to something else, and proposes loc2 and loc3
        ResolvedLocator changedLoc1 = new ResolvedLocator("e1", LocatorStrategy.TEXT, "Save", null, "page.getByText(\"Save\")");
        ResolvedLocatorSet proposed = ResolvedLocatorSet.of(changedLoc1, loc2, loc3);

        // Freeze in CODE: mergeKeepingAccepted must discard changedLoc1 and keep loc1
        ResolvedLocatorSet merged = accepted.mergeKeepingAccepted(proposed);
        assertThat(merged.size()).isEqualTo(3);
        assertThat(merged.findByElementRefId("e1").get().strategy()).isEqualTo(LocatorStrategy.TEST_ID);
        assertThat(merged.findByElementRefId("e1").get().value()).isEqualTo("save-btn");
        assertThat(merged.findByElementRefId("e2")).contains(loc2);
        assertThat(merged.findByElementRefId("e3")).contains(loc3);

        // Validation: loc3 fails with BLOCKING violation
        ValidationViolation blockingViolation = new ValidationViolation(
                loc3.expression(), "e3", "NOT_UNIQUE", 2, Severity.BLOCKING, "Matched 2 elements"
        );
        ValidationViolation advisoryViolation = new ValidationViolation(
                loc2.expression(), "e2", "TEXT_FALLBACK", 1, Severity.ADVISORY, "Advisory note"
        );
        ValidationResult validation = ValidationResult.of(blockingViolation, advisoryViolation);

        assertThat(validation.passed()).isFalse();
        assertThat(validation.blocking()).containsExactly(blockingViolation);
        assertThat(validation.advisory()).containsExactly(advisoryViolation);

        // retainingPassing should retain e1 and e2, but drop e3
        ResolvedLocatorSet retained = merged.retainingPassing(validation);
        assertThat(retained.size()).isEqualTo(2);
        assertThat(retained.findByElementRefId("e1")).isPresent();
        assertThat(retained.findByElementRefId("e2")).isPresent();
        assertThat(retained.findByElementRefId("e3")).isEmpty();
    }

    @Test
    void testValidationResultMerge() {
        ValidationViolation v1 = new ValidationViolation("expr1", "e1", "BANNED_PATTERN", Severity.BLOCKING, "Detail 1");
        ValidationViolation v2 = new ValidationViolation("expr2", "e2", "NOT_UNIQUE", Severity.BLOCKING, "Detail 2");
        ValidationViolation v3 = new ValidationViolation("expr3", "e3", "WARN", Severity.ADVISORY, "Detail 3");

        ValidationResult r1 = ValidationResult.of(v1);
        ValidationResult r2 = ValidationResult.of(v2, v3);

        ValidationResult merged = ValidationResult.merge(r1, r2);
        assertThat(merged.violations()).hasSize(3);
        assertThat(merged.blocking()).containsExactly(v1, v2);
        assertThat(merged.advisory()).containsExactly(v3);
        assertThat(merged.passed()).isFalse();

        ValidationResult emptyMerged = ValidationResult.merge(ValidationResult.empty(), null);
        assertThat(emptyMerged.passed()).isTrue();
    }

    @Test
    void testVerificationResultAndConfidenceTier() {
        VerificationResult passedVerify = VerificationResult.passed(1200);
        assertThat(passedVerify.isPassed()).isTrue();
        assertThat(passedVerify.isFailure()).isFalse();
        assertThat(passedVerify.durationMs()).isEqualTo(1200);

        VerificationResult flakeVerify = VerificationResult.passedAfterFlake(2400);
        assertThat(flakeVerify.isPassed()).isTrue();
        assertThat(flakeVerify.outcome()).isEqualTo("PASSED_AFTER_FLAKE");

        VerificationResult failedVerify = VerificationResult.failed("Stacktrace tail");
        assertThat(failedVerify.isFailure()).isTrue();
        assertThat(failedVerify.isPassed()).isFalse();
        assertThat(failedVerify.failureTail()).isEqualTo("Stacktrace tail");

        VerificationResult skippedVerify = VerificationResult.skipped("NO_SESSION");
        assertThat(skippedVerify.isSkipped()).isTrue();
        assertThat(skippedVerify.skipReason()).isEqualTo("NO_SESSION");

        PageSnapshot fullSnapshot = new PageSnapshot("s1", "http://app", "<html></html>", List.of(), Instant.now());
        ParsedTestPlan fullPlan = new ParsedTestPlan("checkout", "desc", List.of(), List.of(fullSnapshot), false, false);

        PageSnapshot degradedSnapshot = new PageSnapshot("s2", "http://app", null, List.of(), Instant.now());
        ParsedTestPlan degradedPlan = new ParsedTestPlan("checkout", "desc", List.of(), List.of(degradedSnapshot), false, false);

        assertThat(ConfidenceTier.from(passedVerify, fullPlan)).isEqualTo(ConfidenceTier.VERIFIED);
        assertThat(ConfidenceTier.from(skippedVerify, fullPlan)).isEqualTo(ConfidenceTier.STATIC_ONLY);
        assertThat(ConfidenceTier.from(skippedVerify, degradedPlan)).isEqualTo(ConfidenceTier.STATIC_ONLY_DEGRADED);
    }

    @Test
    void testExistingCodeContextDigests() {
        MethodDigest md = new MethodDigest("clickSubmit", "void clickSubmit()", "Clicks submit button", List.of("submit-btn"));
        LocatorDigest ld = new LocatorDigest("submitButton", "page.getByTestId(\"submit-btn\")");
        PageObjectDigest pod = new PageObjectDigest("LoginPage", "pages/LoginPage.java", List.of(md), List.of(ld));

        ExistingCodeContext ctx = new ExistingCodeContext("auth", List.of(pod));
        assertThat(ctx.targetArea()).isEqualTo("auth");
        assertThat(ctx.pageObjects()).hasSize(1);
        assertThat(ctx.digests().get(0).className()).isEqualTo("LoginPage");
        assertThat(ctx.digests().get(0).methods().get(0).name()).isEqualTo("clickSubmit");
        assertThat(ctx.digests().get(0).locators().get(0).fieldName()).isEqualTo("submitButton");
    }

    @Test
    void testTestGenerationJobHelpers() {
        TestGenerationJob job = TestGenerationJob.newJob(
                UUID.randomUUID(), "v2:k1", "account-settings", "Update email", "h1", "m1", Path.of("rec")
        );
        assertThat(job.generatedTestClassName()).isEqualTo("AccountSettingsTest");
        assertThat(job.targetEnvironment()).isEqualTo("staging");

        ParsedTestPlan plan = new ParsedTestPlan("account-settings", "Update email", List.of(), List.of(), false, false);
        TestGenerationJob jobWithPlan = job.withPlan(plan);
        assertThat(jobWithPlan.plan()).isEqualTo(plan);
    }
}
