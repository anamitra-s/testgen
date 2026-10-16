package com.company.testgen.phases;

import com.company.testgen.llm.LlmClient;
import com.company.testgen.llm.ResolveRequest;
import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.Phase;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.model.ValidationResult;
import com.company.testgen.observability.Mdc;
import com.company.testgen.orchestrator.CorrectiveFeedbackBuilder;
import com.company.testgen.persistence.RetryAttemptRepository;
import com.company.testgen.persistence.ValidationResultRepository;
import com.company.testgen.validation.LocatorGrammarValidator;
import com.company.testgen.validation.StaticDomValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Resolve phase executing the bounded Resolve + L0/L1 retry loop with code-enforced locator freeze (§6.6, §6.13).
 */
public class ResolvePhase {

    private static final Logger log = LoggerFactory.getLogger(ResolvePhase.class);

    private final LlmClient llm;
    private final LocatorGrammarValidator grammarValidator;
    private final StaticDomValidator staticValidator;
    private final RetryAttemptRepository attempts;
    private final ValidationResultRepository validations;
    private final CorrectiveFeedbackBuilder feedbackBuilder;
    private final int maxAttempts;

    public record ResolveResult(
            ResolvedLocatorSet accepted,
            ValidationResult validation,
            int attemptsUsed
    ) {}

    public ResolvePhase(
            LlmClient llm,
            LocatorGrammarValidator grammarValidator,
            StaticDomValidator staticValidator,
            RetryAttemptRepository attempts,
            ValidationResultRepository validations,
            CorrectiveFeedbackBuilder feedbackBuilder,
            int maxAttempts
    ) {
        this.llm = llm;
        this.grammarValidator = grammarValidator != null ? grammarValidator : new LocatorGrammarValidator();
        this.staticValidator = staticValidator != null ? staticValidator : new StaticDomValidator();
        this.attempts = attempts;
        this.validations = validations;
        this.feedbackBuilder = feedbackBuilder != null ? feedbackBuilder : new CorrectiveFeedbackBuilder();
        this.maxAttempts = maxAttempts > 0 ? maxAttempts : 3;
    }

    public ResolvePhase(
            LlmClient llm,
            LocatorGrammarValidator grammarValidator,
            StaticDomValidator staticValidator,
            CorrectiveFeedbackBuilder feedbackBuilder,
            int maxAttempts
    ) {
        this(llm, grammarValidator, staticValidator, null, null, feedbackBuilder, maxAttempts);
    }

    /**
     * Executes the locator resolution and validation loop (§6.6, §6.13).
     */
    public ResolveResult execute(TestGenerationJob job, ParsedTestPlan safePlan, ExistingCodeContext context) {
        Objects.requireNonNull(job, "job cannot be null");
        Objects.requireNonNull(safePlan, "safePlan cannot be null");

        ResolvedLocatorSet accepted = ResolvedLocatorSet.empty();
        ValidationResult validation = null;
        String feedback = null;
        int attemptsUsed = 0;

        if (llm != null) {
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                attemptsUsed = attempt + 1;
                Mdc.phase(Phase.RESOLVE, attempt);
                Long attemptId = attempts != null ? attempts.begin(job.id(), attempt, Phase.RESOLVE) : null;

                ResolvedLocatorSet proposed = llm.resolveLocators(
                        new ResolveRequest(job, safePlan, context, feedback, attempt, attemptId));

                // Enforce the freeze in CODE — the prompt instruction is only a hint (§6.13)
                ResolvedLocatorSet merged = accepted.mergeKeepingAccepted(proposed);

                validation = ValidationResult.merge(
                        grammarValidator.validate(merged, safePlan),       // L0
                        staticValidator.validate(merged, safePlan));       // L1

                if (validations != null && attemptId != null) {
                    validations.saveAll(attemptId, validation);
                }
                if (attempts != null && attemptId != null) {
                    attempts.finish(attemptId, validation.passed());
                }

                accepted = merged.retainingPassing(validation);

                if (validation.passed()) {
                    log.info("Locator validation PASSED on attempt {}", attempt);
                    break;
                }
                feedback = feedbackBuilder.build(validation.blocking(), accepted);
            }
        }

        return new ResolveResult(accepted, validation, attemptsUsed);
    }

    public LlmClient llm() {
        return llm;
    }

    public LocatorGrammarValidator grammarValidator() {
        return grammarValidator;
    }

    public StaticDomValidator staticValidator() {
        return staticValidator;
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
