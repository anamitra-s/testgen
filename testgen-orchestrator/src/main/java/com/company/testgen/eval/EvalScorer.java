package com.company.testgen.eval;

import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.orchestrator.PipelineOutcome;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic scoring function for evaluation outcomes (§8.3).
 * Compares actual resolved locators with reference expected locators.
 * Exact match compares semantics, not rendered strings (whitespace/formatting differences
 * must not register as regressions).
 * Cases marked expectedToEscalate: true score as passing when the pipeline escalates to
 * NEEDS_HUMAN_REVIEW rather than emitting a false-confident locator.
 */
public class EvalScorer {

    public CaseScore score(EvalCase c, PipelineOutcome outcome) {
        Objects.requireNonNull(c, "c cannot be null");
        Objects.requireNonNull(outcome, "outcome cannot be null");

        Map<String, ResolvedLocator> expected = c.expectedLocators();
        Map<String, ResolvedLocator> actual = outcome.finalLocators();

        int exact = 0;
        int strategyOnly = 0;
        for (Map.Entry<String, ResolvedLocator> e : expected.entrySet()) {
            ResolvedLocator a = actual.get(e.getKey());
            if (a == null) {
                continue;
            }
            if (isExact(a, e.getValue())) {
                exact++;
            }
            if (a.strategy() != null && e.getValue().strategy() != null
                    && a.strategy() == e.getValue().strategy()) {
                strategyOnly++;
            }
        }

        boolean firstAttemptPass;
        if (c.expectedToEscalate()) {
            // Case is curated specifically to verify proper escalation without false confidence (§8.2)
            firstAttemptPass = outcome.escalated();
        } else {
            firstAttemptPass = outcome.attemptsUsed() == 1 && !outcome.escalated();
        }

        return new CaseScore(
                c.caseId(),
                firstAttemptPass,
                outcome.attemptsUsed(),
                outcome.escalated(),
                exact,
                strategyOnly,
                expected.size(),
                outcome.tokenUsage(),
                outcome.durationMs(),
                outcome.blockingReasonCodes()
        );
    }

    /**
     * Exact match compares SEMANTICS, not rendered strings — whitespace and
     * formatting differences in the renderer must not register as regressions (§8.3).
     */
    public boolean isExact(ResolvedLocator a, ResolvedLocator b) {
        if (a == null || b == null) {
            return false;
        }
        return a.strategy() == b.strategy()
                && Objects.equals(norm(a.value()), norm(b.value()))
                && Objects.equals(lower(a.role()), lower(b.role()));
    }

    public static String norm(String s) {
        if (s == null) {
            return null;
        }
        return s.trim().replaceAll("\\s+", " ");
    }

    public static String lower(String s) {
        if (s == null) {
            return null;
        }
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
