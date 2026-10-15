package com.company.testgen.eval;

import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.TokenUsage;
import com.company.testgen.orchestrator.PipelineOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for EvalScorer (§8.3, §9.5).
 * Tests deterministic semantic comparison of resolved locators vs reference reference locators.
 */
class EvalScorerTest {

    @Test
    @DisplayName("EvalScorer scores exact semantic matches normalizing whitespace and role casing")
    void testExactSemanticMatches() {
        EvalScorer scorer = new EvalScorer();

        Map<String, ResolvedLocator> expected = Map.of(
                "btn-save", new ResolvedLocator("btn-save", LocatorStrategy.ROLE_NAME, "Save Changes", "button"),
                "input-email", new ResolvedLocator("input-email", LocatorStrategy.LABEL, "Work Email"),
                "btn-test", new ResolvedLocator("btn-test", LocatorStrategy.TEST_ID, "submit-action")
        );

        EvalCase evalCase = new EvalCase(
                "case-001", "settings", "Save profile settings", "", null, "easy", List.of(), "", "active",
                "", null, expected, null, null
        );

        // Actual locators with formatting variations that must normalize to exact matches:
        // 1. Role name with uppercase role "BUTTON" and extra whitespace in name
        // 2. Label with leading/trailing spaces
        // 3. Exact test ID
        List<ResolvedLocator> actualList = List.of(
                new ResolvedLocator("btn-save", LocatorStrategy.ROLE_NAME, "  Save   Changes  ", "BUTTON"),
                new ResolvedLocator("input-email", LocatorStrategy.LABEL, "  Work Email  "),
                new ResolvedLocator("btn-test", LocatorStrategy.TEST_ID, "submit-action")
        );

        PipelineOutcome outcome = PipelineOutcome.success(
                UUID.randomUUID(),
                "DONE",
                new ResolvedLocatorSet(actualList),
                List.of(),
                850L,
                1,
                new TokenUsage(100, 50, 20, 10)
        );

        CaseScore score = scorer.score(evalCase, outcome);

        assertThat(score.caseId()).isEqualTo("case-001");
        assertThat(score.firstAttemptPass()).isTrue();
        assertThat(score.attemptsUsed()).isEqualTo(1);
        assertThat(score.escalated()).isFalse();
        assertThat(score.locatorExactMatches()).isEqualTo(3);
        assertThat(score.locatorStrategyMatches()).isEqualTo(3);
        assertThat(score.referenceLocatorCount()).isEqualTo(3);
        assertThat((double) score.locatorExactMatches() / score.referenceLocatorCount()).isEqualTo(1.0);
        assertThat((double) score.locatorStrategyMatches() / score.referenceLocatorCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("EvalScorer distinguishes strategy-only matches from exact matches")
    void testStrategyOnlyVersusExact() {
        EvalScorer scorer = new EvalScorer();

        Map<String, ResolvedLocator> expected = Map.of(
                "e1", new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "btn-save"),
                "e2", new ResolvedLocator("e2", LocatorStrategy.LABEL, "First Name")
        );

        EvalCase evalCase = new EvalCase(
                "case-002", "profile", "Profile name test", "", null, "easy", List.of(), "", "active",
                "", null, expected, null, null
        );

        // e1: exact match
        // e2: strategy matches (LABEL), but value is different ("Given Name" != "First Name")
        List<ResolvedLocator> actualList = List.of(
                new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "btn-save"),
                new ResolvedLocator("e2", LocatorStrategy.LABEL, "Given Name")
        );

        PipelineOutcome outcome = PipelineOutcome.success(
                UUID.randomUUID(),
                "DONE",
                new ResolvedLocatorSet(actualList),
                List.of(),
                900L,
                1,
                new TokenUsage(120, 40, 0, 0)
        );

        CaseScore score = scorer.score(evalCase, outcome);

        assertThat(score.locatorExactMatches()).isEqualTo(1); // e1 only
        assertThat(score.locatorStrategyMatches()).isEqualTo(2); // both match strategy
        assertThat((double) score.locatorExactMatches() / score.referenceLocatorCount()).isEqualTo(0.5);
        assertThat((double) score.locatorStrategyMatches() / score.referenceLocatorCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("EvalScorer correctly detects multi-attempt success and escalation")
    void testMultiAttemptAndEscalation() {
        EvalScorer scorer = new EvalScorer();

        Map<String, ResolvedLocator> expected = Map.of(
                "e1", new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "confirm-btn")
        );

        EvalCase evalCase = new EvalCase(
                "case-003", "checkout", "Checkout confirmation", "", null, "hard", List.of(), "", "active",
                "", null, expected, null, null
        );

        // Case A: 2 attempts used, success (firstAttemptPass is false)
        PipelineOutcome outcomeA = PipelineOutcome.success(
                UUID.randomUUID(), "DONE",
                new ResolvedLocatorSet(List.of(new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "confirm-btn"))),
                List.of(), 1500L, 2, new TokenUsage(200, 80, 50, 30)
        );
        CaseScore scoreA = scorer.score(evalCase, outcomeA);
        assertThat(scoreA.firstAttemptPass()).isFalse();
        assertThat(scoreA.attemptsUsed()).isEqualTo(2);
        assertThat(scoreA.escalated()).isFalse();

        // Case B: Escalated outcome (needs human review)
        PipelineOutcome outcomeB = PipelineOutcome.escalated(
                UUID.randomUUID(), "NOT_UNIQUE",
                new ResolvedLocatorSet(List.of()),
                List.of(),
                3200L, 3, new TokenUsage(400, 150, 100, 50),
                List.of("NOT_UNIQUE")
        );
        CaseScore scoreB = scorer.score(evalCase, outcomeB);
        assertThat(scoreB.firstAttemptPass()).isFalse();
        assertThat(scoreB.attemptsUsed()).isEqualTo(3);
        assertThat(scoreB.escalated()).isTrue();
        assertThat(scoreB.locatorExactMatches()).isEqualTo(0);
        assertThat(scoreB.blockingReasonCodes()).contains("NOT_UNIQUE");
    }

    @Test
    @DisplayName("EvalScorer isExact handles nulls and strategy mismatches")
    void testIsExactNullAndMismatch() {
        EvalScorer scorer = new EvalScorer();

        ResolvedLocator loc = new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "btn");

        assertThat(scorer.isExact(null, loc)).isFalse();
        assertThat(scorer.isExact(loc, null)).isFalse();
        assertThat(scorer.isExact(null, null)).isFalse();

        ResolvedLocator diffStrategy = new ResolvedLocator("e1", LocatorStrategy.TEXT, "btn");
        assertThat(scorer.isExact(loc, diffStrategy)).isFalse();

        ResolvedLocator diffValue = new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "btn-different");
        assertThat(scorer.isExact(loc, diffValue)).isFalse();

        ResolvedLocator roleBtn1 = new ResolvedLocator("e2", LocatorStrategy.ROLE_NAME, "Submit", "button");
        ResolvedLocator roleBtn2 = new ResolvedLocator("e2", LocatorStrategy.ROLE_NAME, "Submit", "link");
        assertThat(scorer.isExact(roleBtn1, roleBtn2)).isFalse();
    }

    @Test
    @DisplayName("EvalScorer throws NullPointerException on null parameters")
    void testNullValidation() {
        EvalScorer scorer = new EvalScorer();

        assertThatThrownBy(() -> scorer.score(null, PipelineOutcome.failure(
                UUID.randomUUID(), "err", 1, 100L
        ))).isInstanceOf(NullPointerException.class);

        EvalCase evalCase = new EvalCase(
                "case-004", "a", "b", "", null, "easy", List.of(), "", "active", "", null, Map.of(), null, null
        );
        assertThatThrownBy(() -> scorer.score(evalCase, null))
                .isInstanceOf(NullPointerException.class);
    }
}
