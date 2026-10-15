package com.company.testgen.gitlab;

import com.company.testgen.model.ConfidenceTier;
import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.MethodDigest;
import com.company.testgen.model.PageObjectDigest;
import com.company.testgen.model.RedactionReport;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.Severity;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.model.TokenUsage;
import com.company.testgen.model.ValidationResult;
import com.company.testgen.model.ValidationViolation;
import com.company.testgen.model.VerificationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GitLabTemplatesAndNamerTest {

    @Test
    @DisplayName("BranchNamer formats branch names as testgen/<area>/<jobId>")
    void testBranchNamer() {
        UUID jobId = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        TestGenerationJob job = new TestGenerationJob(
                jobId, "v2:key1", "billing", "Add test", "jenkins", "scratch",
                "bundle-v1", "claude-sonnet-5", "1.0.0", "2.0.0", "sha-1",
                "worker-1", 1L, Path.of(".")
        );

        String branchName = BranchNamer.forJob(job);
        assertThat(branchName).isEqualTo("testgen/billing/12345678-1234-1234-1234-123456789abc");
        assertThat(BranchNamer.forAreaAndJobId("composer", jobId))
                .isEqualTo("testgen/composer/12345678-1234-1234-1234-123456789abc");
        assertThat(BranchNamer.extractJobId(branchName)).contains(jobId);
        assertThat(BranchNamer.extractArea(branchName)).contains("billing");
    }

    @Test
    @DisplayName("MergeRequestTemplate renders full standard MR description")
    void testRenderStandardMergeRequest() {
        UUID jobId = UUID.randomUUID();
        TestGenerationJob job = new TestGenerationJob(
                jobId, "v2:test-key", "dashboard", "Dashboard date filter persists across reload",
                "12345", "scratch", "bundle-v7", "claude-sonnet-5", "1.4.2", "2.0.0", "7c2e91a",
                "worker-1", 1L, Path.of(".")
        );

        ResolvedLocator loc = new ResolvedLocator(
                "e3", LocatorStrategy.TEST_ID, "date-filter-trigger", null,
                "getByTestId(\"date-filter-trigger\")"
        );
        ResolvedLocatorSet locators = new ResolvedLocatorSet(List.of(loc));

        PageObjectDigest pod = new PageObjectDigest(
                "DashboardPage", "src/test/java/pages/DashboardPage.java",
                List.of(new MethodDigest("openDateFilter", "()V"), new MethodDigest("applyFilter", "()V")),
                List.of()
        );
        ExistingCodeContext context = new ExistingCodeContext("dashboard", List.of(pod));

        RedactionReport redaction = RedactionReport.builder()
                .ruleApplied("EMAIL")
                .ruleApplied("PHONE")
                .attributeDropped("value")
                .attributeDropped("src")
                .urlNormalized()
                .urlNormalized()
                .urlNormalized()
                .urlNormalized()
                .urlNormalized()
                .urlNormalized()
                .urlNormalized()
                .build();
        TokenUsage tokenUsage = new TokenUsage(32400, 18100, 0, 3900);
        VerificationResult verify = VerificationResult.passed(41000);

        String title = MergeRequestTemplate.title(job, ConfidenceTier.VERIFIED);
        assertThat(title).isEqualTo("[auto][VERIFIED] Dashboard date filter persists across reload");

        String description = MergeRequestTemplate.render(
                job,
                ConfidenceTier.VERIFIED,
                verify,
                locators,
                context,
                redaction,
                tokenUsage,
                Duration.ofSeconds(252),
                1,
                new BigDecimal("0.14")
        );

        // Validation table
        assertThat(description).contains("### Validation summary");
        assertThat(description).contains("| `e3` | TEST_ID | `getByTestId(\"date-filter-trigger\")` |");

        // L2 Verification
        assertThat(description).contains("### Verification (L2)");
        assertThat(description).contains("passed against `staging` in 41s");

        // Code reuse
        assertThat(description).contains("### Code reuse");
        assertThat(description).contains("Reused 2 existing methods");
        assertThat(description).contains("DashboardPage.openDateFilter()");

        // Provenance
        assertThat(description).contains("### Provenance");
        assertThat(description).contains("`model=claude-sonnet-5`");
        assertThat(description).contains("`orchestrator=1.4.2`");
        assertThat(description).contains("`base=7c2e91a`");

        // Redaction report collapsible
        assertThat(description).contains("<details><summary>Redaction report</summary>");
        assertThat(description).contains("2 PII rules applied, 2 attribute drops, 7 URLs normalized");
        assertThat(description).contains("</details>");

        // Human approval note
        assertThat(description).contains("A human approval is required — this MR will not auto-merge.");
    }

    @Test
    @DisplayName("MergeRequestTemplate renders Escalation Draft MR description")
    void testRenderEscalationDraftMergeRequest() {
        UUID jobId = UUID.randomUUID();
        TestGenerationJob job = TestGenerationJob.newJob(
                jobId, "v2:escalate-key", "dashboard", "Dashboard table row actions", "bundle-v7", "claude-sonnet-5", Path.of(".")
        );

        String title = MergeRequestTemplate.draftTitle(job, ConfidenceTier.STATIC_ONLY);
        assertThat(title).isEqualTo("[Draft][NEEDS REVIEW][STATIC_ONLY] Dashboard table row actions");

        ValidationViolation violation = new ValidationViolation(
                "getByRole(button, name=\"Actions\")", "e12", "NOT_UNIQUE", 12, Severity.BLOCKING,
                "matched 12 elements; must match exactly 1"
        );
        ValidationResult validation = new ValidationResult(List.of(violation));

        ResolvedLocator passing = new ResolvedLocator(
                "e1", LocatorStrategy.TEST_ID, "submit-btn", null,
                "getByTestId(\"submit-btn\")"
        );
        ResolvedLocatorSet accepted = new ResolvedLocatorSet(List.of(passing));

        String draftDesc = MergeRequestTemplate.renderEscalation(
                job, ConfidenceTier.STATIC_ONLY, "VALIDATION_EXHAUSTED", accepted, validation, 3
        );

        assertThat(draftDesc).contains("## ⚠️ NEEDS HUMAN REVIEW — validation could not be completed");
        assertThat(draftDesc).contains("3 attempts exhausted");
        assertThat(draftDesc).contains("reason: `VALIDATION_EXHAUSTED`");
        assertThat(draftDesc).contains("### ❌ Unresolved");
        assertThat(draftDesc).contains("`e12`");
        assertThat(draftDesc).contains("NOT_UNIQUE");
        assertThat(draftDesc).contains("matches: 12");
        assertThat(draftDesc).contains("Suggested fix:** Add a unique `data-testid` attribute");
        assertThat(draftDesc).contains("### ✅ Resolved (1) — committed for reference");
    }
}
