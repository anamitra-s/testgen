package com.company.testgen.phases;

import com.company.testgen.llm.GenerateRequest;
import com.company.testgen.llm.LlmClient;
import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.GeneratedFile;
import com.company.testgen.model.GeneratedFileSet;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.TestGenerationJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Generate phase generating Playwright-Java test code and Page Objects (§6.12).
 * Integrates with LintPhase to validate syntax, locator invariance, Checkstyle, and packaging.
 * Executes at most ONE regeneration pass with lint feedback before escalating with EscalationRequired.
 */
public class GeneratePhase {

    private static final Logger log = LoggerFactory.getLogger(GeneratePhase.class);

    private final LlmClient llm;
    private final LintPhase lintPhase;

    public GeneratePhase(LlmClient llm, LintPhase lintPhase) {
        this.llm = Objects.requireNonNull(llm, "llm must not be null");
        this.lintPhase = Objects.requireNonNull(lintPhase, "lintPhase must not be null");
    }

    public List<GeneratedFile> execute(TestGenerationJob job, ResolvedLocatorSet locators, ExistingCodeContext context) {
        Objects.requireNonNull(job, "job must not be null");
        return execute(job.plan(), locators, context, job.baseCommitSha());
    }

    public List<GeneratedFile> execute(ParsedTestPlan plan, ResolvedLocatorSet locators,
                                       ExistingCodeContext context, String baseSha) {
        String targetArea = plan != null ? plan.targetArea() : null;
        log.info("Starting initial code generation pass for targetArea='{}' baseSha='{}'", targetArea, baseSha);

        GenerateRequest initialRequest = new GenerateRequest(plan, locators, context, baseSha);
        GeneratedFileSet raw = llm.generateCode(initialRequest);

        LintResult lint = lintPhase.check(raw, locators, targetArea);
        if (!lint.passed()) {
            log.warn("Initial generation failed lint checks: {}. Attempting one regeneration pass with feedback...",
                    lint.report());

            // Exactly ONE regeneration pass with the lint output as feedback (§6.12)
            GenerateRequest retryRequest = initialRequest.withLintFeedback(lint.report());
            raw = llm.generateCode(retryRequest);

            lint = lintPhase.check(raw, locators, targetArea);
            if (!lint.passed()) {
                log.error("Regeneration failed lint checks on second attempt: {}", lint.report());
                throw new EscalationRequired("LINT_FAILURE", lint.report());
            }
            log.info("Regeneration pass successfully resolved lint violations.");
        } else {
            log.info("Initial generation passed all lint checks.");
        }

        return raw.files();
    }
}
