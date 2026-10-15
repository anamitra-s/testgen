package com.company.testgen.validation;

import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.RecordedAction;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocatorGrammarValidatorTest {

    @Test
    @DisplayName("L0 passes valid complete locator set")
    void testPassesValidSet() {
        ParsedTestPlan plan = new ParsedTestPlan(
                "billing", "desc",
                List.of(new RecordedAction(0, "click", "e1", null, "s1")),
                List.of(), false, false
        );

        ResolvedLocatorSet set = ResolvedLocatorSet.of(
                new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "save-btn")
        );

        LocatorGrammarValidator validator = new LocatorGrammarValidator();
        ValidationResult result = validator.validate(set, plan);

        assertThat(result.passed()).isTrue();
        assertThat(result.blocking()).isEmpty();
    }

    @Test
    @DisplayName("L0 flags TEXT strategy as ADVISORY (LOW_CONFIDENCE_STRATEGY)")
    void testAdvisoryForTextStrategy() {
        ParsedTestPlan plan = new ParsedTestPlan(
                "billing", "desc",
                List.of(new RecordedAction(0, "click", "e1", null, "s1")),
                List.of(), false, false
        );

        ResolvedLocatorSet set = ResolvedLocatorSet.of(
                new ResolvedLocator("e1", LocatorStrategy.TEXT, "Save Changes")
        );

        LocatorGrammarValidator validator = new LocatorGrammarValidator();
        ValidationResult result = validator.validate(set, plan);

        assertThat(result.passed()).isTrue(); // No blocking violations
        assertThat(result.advisory()).hasSize(1);
        assertThat(result.advisory().get(0).reasonCode()).isEqualTo("LOW_CONFIDENCE_STRATEGY");
    }

    @Test
    @DisplayName("L0 blocks missing and duplicate locators for completeness")
    void testCompletenessViolations() {
        ParsedTestPlan plan = new ParsedTestPlan(
                "billing", "desc",
                List.of(
                        new RecordedAction(0, "click", "e1", null, "s1"),
                        new RecordedAction(1, "click", "e2", null, "s1")
                ),
                List.of(), false, false
        );

        // e1 has duplicates, e2 is missing
        ResolvedLocatorSet set = ResolvedLocatorSet.of(
                new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "btn-1"),
                new ResolvedLocator("e1", LocatorStrategy.LABEL, "Btn One")
        );

        LocatorGrammarValidator validator = new LocatorGrammarValidator();
        ValidationResult result = validator.validate(set, plan);

        assertThat(result.passed()).isFalse();
        assertThat(result.blocking()).anyMatch(v -> "MISSING_LOCATOR".equals(v.reasonCode()) && "e2".equals(v.elementRefId()));
        assertThat(result.blocking()).anyMatch(v -> "DUPLICATE_LOCATOR".equals(v.reasonCode()) && "e1".equals(v.elementRefId()));
    }

    @Test
    @DisplayName("L0 blocks unknown element ref, empty value, and invalid ARIA role")
    void testMalformedLocators() {
        ParsedTestPlan plan = new ParsedTestPlan(
                "billing", "desc",
                List.of(new RecordedAction(0, "click", "e1", null, "s1")),
                List.of(), false, false
        );

        ResolvedLocatorSet set = ResolvedLocatorSet.of(
                new ResolvedLocator("unknown-ref", LocatorStrategy.TEST_ID, "btn"),
                new ResolvedLocator("e1", LocatorStrategy.ROLE_NAME, "  ", "not_a_role")
        );

        LocatorGrammarValidator validator = new LocatorGrammarValidator();
        ValidationResult result = validator.validate(set, plan);

        assertThat(result.passed()).isFalse();
        assertThat(result.blocking()).anyMatch(v -> "UNKNOWN_ELEMENT_REF".equals(v.reasonCode()));
        assertThat(result.blocking()).anyMatch(v -> "EMPTY_VALUE".equals(v.reasonCode()));
        assertThat(result.blocking()).anyMatch(v -> "INVALID_ROLE".equals(v.reasonCode()));
    }

    @Test
    @DisplayName("L0 blocks all banned patterns (:nth-child, >>, .nth(, xpath=, generated IDs)")
    void testBannedPatterns() {
        ParsedTestPlan plan = new ParsedTestPlan(
                "test", "desc",
                List.of(new RecordedAction(0, "click", "e1", null, "s1")),
                List.of(), false, false
        );

        String[] bannedValues = {
                "div:nth-child(2)",
                "div >> button",
                "table.nth(0)",
                "xpath=//div/button",
                "generated-id-4921",
                ":r1a:",
                "mui-45892",
                "ember102",
                "ng-771"
        };

        LocatorGrammarValidator validator = new LocatorGrammarValidator();
        for (String bannedVal : bannedValues) {
            ResolvedLocatorSet set = ResolvedLocatorSet.of(
                    new ResolvedLocator("e1", LocatorStrategy.TEST_ID, bannedVal)
            );
            ValidationResult result = validator.validate(set, plan);
            assertThat(result.blocking())
                    .as("Expected banned pattern rejection for: " + bannedVal)
                    .anyMatch(v -> "BANNED_PATTERN".equals(v.reasonCode()));
        }
    }

    @Test
    @DisplayName("L0 throws NullPointerException on null set or plan")
    void testNullValidation() {
        LocatorGrammarValidator validator = new LocatorGrammarValidator();
        ParsedTestPlan plan = new ParsedTestPlan(
                "test", "desc", List.of(), List.of(), false, false
        );
        ResolvedLocatorSet set = ResolvedLocatorSet.of();

        assertThatThrownBy(() -> validator.validate(null, plan))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> validator.validate(set, null))
                .isInstanceOf(NullPointerException.class);
    }
}
