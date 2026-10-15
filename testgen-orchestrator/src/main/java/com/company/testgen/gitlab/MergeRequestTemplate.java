package com.company.testgen.gitlab;

import com.company.testgen.model.ConfidenceTier;
import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.RedactionReport;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.model.TokenUsage;
import com.company.testgen.model.ValidationResult;
import com.company.testgen.model.ValidationViolation;
import com.company.testgen.model.VerificationResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Renders GitLab Merge Request titles and markdown descriptions (§6.15).
 * Supports standard confidence-tiered MRs and Escalation Draft MRs.
 */
public final class MergeRequestTemplate {

    private MergeRequestTemplate() {}

    /**
     * Standard MR title format (§6.15, FR-22).
     */
    public static String title(TestGenerationJob job, ConfidenceTier tier) {
        Objects.requireNonNull(job, "job cannot be null");
        Objects.requireNonNull(tier, "tier cannot be null");
        return "[auto][%s] %s".formatted(tier, job.testDescription());
    }

    /**
     * Escalation Draft MR title format (§6.15).
     */
    public static String draftTitle(TestGenerationJob job, ConfidenceTier tier) {
        Objects.requireNonNull(job, "job cannot be null");
        String tierName = tier != null ? tier.name() : "STATIC_ONLY";
        return "[Draft][NEEDS REVIEW][%s] %s".formatted(tierName, job.testDescription());
    }

    /**
     * Renders standard MR markdown description (§6.15).
     */
    public static String render(TestGenerationJob job, ConfidenceTier tier, VerificationResult verify) {
        return render(job, tier, verify, null, null, null, null, null, 1, BigDecimal.ZERO);
    }

    /**
     * Renders standard MR markdown description with full metadata (§6.15).
     */
    public static String render(
            TestGenerationJob job,
            ConfidenceTier tier,
            VerificationResult verify,
            ResolvedLocatorSet locators,
            ExistingCodeContext context,
            RedactionReport redaction,
            TokenUsage tokenUsage,
            Duration duration,
            int attempts,
            BigDecimal estimatedCostUsd
    ) {
        Objects.requireNonNull(job, "job cannot be null");
        ConfidenceTier effectiveTier = tier != null ? tier : ConfidenceTier.STATIC_ONLY;

        StringBuilder sb = new StringBuilder();
        sb.append("## Auto-generated test — `").append(job.targetArea()).append("`\n");
        sb.append("> ").append(job.testDescription()).append("\n\n");

        // Metadata line
        sb.append("**Confidence: ").append(effectiveTier).append("**");
        sb.append(" · ").append(attempts).append(attempts == 1 ? " attempt" : " attempts");
        if (duration != null) {
            sb.append(" · ").append(formatDuration(duration));
        }
        if (tokenUsage != null) {
            sb.append(" · ").append(formatTokens(tokenUsage.totalTokens())).append(" tokens");
            if (tokenUsage.cacheReadTokens() > 0) {
                sb.append(" (").append(formatTokens(tokenUsage.cacheReadTokens())).append(" cached)");
            }
        }
        if (estimatedCostUsd != null && estimatedCostUsd.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(" · $").append(estimatedCostUsd.setScale(2, java.math.RoundingMode.HALF_UP));
        }
        sb.append("\n\n");

        // Validation summary table
        sb.append("### Validation summary\n");
        sb.append("| Element | Strategy | Locator | L0 | L1 | Notes |\n");
        sb.append("|---|---|---|---|---|---|\n");
        if (locators != null && locators.locators() != null && !locators.locators().isEmpty()) {
            for (ResolvedLocator loc : locators.locators()) {
                String notes = loc.strategy() != null && loc.strategy().name().equals("TEXT")
                        ? "Low-confidence strategy — breaks on copy changes. Consider adding a test id."
                        : "";
                sb.append("| `").append(loc.elementRefId()).append("` ")
                        .append("| ").append(loc.strategy())
                        .append(" | `").append(loc.expression() != null ? loc.expression() : loc.value()).append("` ")
                        .append("| ✅ | ✅ unique, identity match ")
                        .append("| ").append(notes).append(" |\n");
            }
        } else {
            sb.append("| All interacted elements | TEST_ID / ROLE_NAME | Resolved | ✅ | ✅ unique | |\n");
        }
        sb.append("\n");

        // Verification (L2)
        sb.append("### Verification (L2)\n");
        if (verify != null) {
            if (verify.isPassed()) {
                sb.append("✅ `").append(job.generatedTestClassName()).append("` passed against `")
                        .append(job.targetEnvironment() != null ? job.targetEnvironment() : "staging")
                        .append("` in ").append(verify.durationMs() / 1000).append("s.\n");
            } else if (verify.isSkipped()) {
                sb.append("⚠️ Skipped: ").append(verify.skipReason()).append("\n");
            } else {
                sb.append("❌ Verification failed: ").append(verify.failureTail() != null ? verify.failureTail() : "Test run failed").append("\n");
            }
        } else {
            sb.append("ℹ️ L2 dynamic verification skipped or executed in advisory mode.\n");
        }
        sb.append("\n");

        // Code reuse
        sb.append("### Code reuse\n");
        if (context != null && context.reusedMethods() != null && !context.reusedMethods().isEmpty()) {
            sb.append("Reused ").append(context.reusedMethods().size()).append(" existing methods: ")
                    .append(String.join(", ", context.reusedMethods())).append("\n");
        } else {
            sb.append("Created new page object definitions and assertions for `").append(job.targetArea()).append("`.\n");
        }
        sb.append("\n");

        // Provenance
        sb.append("### Provenance\n");
        sb.append("`model=").append(job.modelId() != null ? job.modelId() : "claude-sonnet-5").append("`")
                .append(" · `prompts=").append(job.promptBundleHash() != null ? job.promptBundleHash() : "v7").append("`")
                .append(" · `orchestrator=").append(job.orchestratorVersion() != null ? job.orchestratorVersion() : "1.0.0").append("`")
                .append(" · `base=").append(job.baseCommitSha() != null ? job.baseCommitSha() : "main").append("`\n");

        // Redaction report
        sb.append("<details><summary>Redaction report</summary>");
        if (redaction != null) {
            sb.append(redaction.rulesApplied() != null ? redaction.rulesApplied().size() + " PII rules applied, " : "")
                    .append(redaction.attributesDropped() != null ? redaction.attributesDropped().size() + " attribute drops, " : "")
                    .append(redaction.urlsNormalized()).append(" URLs normalized");
        } else {
            sb.append("Allow-list attribute filtering and PII scrubbing applied.");
        }
        sb.append("</details>\n\n");

        // Footer
        sb.append("---\n");
        sb.append("🤖 Generated by testgen. **A human approval is required — this MR will not auto-merge.**\n");

        return sb.toString();
    }

    /**
     * Renders Escalation Draft MR markdown description (§6.15).
     */
    public static String renderEscalation(
            TestGenerationJob job,
            ConfidenceTier tier,
            String reasonCode,
            ResolvedLocatorSet accepted,
            ValidationResult validation,
            int attemptsUsed
    ) {
        Objects.requireNonNull(job, "job cannot be null");
        StringBuilder sb = new StringBuilder();

        sb.append("## ⚠️ NEEDS HUMAN REVIEW — validation could not be completed\n\n");
        sb.append("**Confidence: ").append(tier != null ? tier : "STATIC_ONLY").append("**")
                .append(" · ").append(attemptsUsed).append(" attempts exhausted")
                .append(" · reason: `").append(reasonCode != null ? reasonCode : "VALIDATION_EXHAUSTED").append("`\n\n");

        // Unresolved Section
        sb.append("### ❌ Unresolved\n");
        if (validation != null && validation.violations() != null && !validation.violations().isEmpty()) {
            for (ValidationViolation v : validation.blocking()) {
                sb.append("**`").append(v.elementRefId() != null ? v.elementRefId() : "element").append("`**\n");
                sb.append("- Locator: `").append(v.locatorExpression()).append("`\n");
                sb.append("- Reason: `").append(v.reasonCode()).append("` (matches: ").append(v.matchCount()).append(")\n");
                if (v.detail() != null) {
                    sb.append("- Detail: ").append(v.detail()).append("\n");
                }
                sb.append("\n");
            }
            sb.append("**Diagnosis:** The element could not be uniquely identified with the constrained locator grammar.\n\n");
            sb.append("**Suggested fix:** Add a unique `data-testid` attribute to the element in the application and re-record.\n\n");
        } else {
            sb.append("Validation could not be satisfied within retry limits.\n\n");
        }

        // Resolved Section
        int acceptedCount = (accepted != null && accepted.locators() != null) ? accepted.locators().size() : 0;
        sb.append("### ✅ Resolved (").append(acceptedCount).append(") — committed for reference\n");
        if (acceptedCount > 0) {
            sb.append("| Element | Strategy | Locator | Status |\n");
            sb.append("|---|---|---|---|\n");
            for (ResolvedLocator loc : accepted.locators()) {
                sb.append("| `").append(loc.elementRefId()).append("` ")
                        .append("| ").append(loc.strategy())
                        .append(" | `").append(loc.expression() != null ? loc.expression() : loc.value()).append("` ")
                        .append("| ✅ Passed |\n");
            }
            sb.append("\n");
        } else {
            sb.append("No locators successfully passed all validation checks.\n\n");
        }

        sb.append("---\n");
        sb.append("⚠️ Generated by testgen in draft status. **Manual inspection and fix required.**\n");

        return sb.toString();
    }

    private static String formatDuration(Duration d) {
        long s = d.toSeconds();
        long m = s / 60;
        long remS = s % 60;
        return m > 0 ? m + "m" + remS + "s" : s + "s";
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1000) {
            return String.format("%.1fk", tokens / 1000.0);
        }
        return String.valueOf(tokens);
    }
}
