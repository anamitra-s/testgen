package com.company.testgen.orchestrator;

import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.Severity;
import com.company.testgen.model.ValidationViolation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorrectiveFeedbackBuilderTest {

    @Test
    @DisplayName("CorrectiveFeedbackBuilder formats failed locators and lists frozen passing locators")
    void testBuildCorrectiveFeedback() {
        ValidationViolation failedViolation = new ValidationViolation(
                "page.getByTestId(\"row-action\")",
                "e2",
                "NOT_UNIQUE",
                3,
                Severity.BLOCKING,
                "matched 3 elements: <button id=\"r1\">, <button id=\"r2\">, <button id=\"r3\">"
        );

        ResolvedLocatorSet current = ResolvedLocatorSet.of(
                new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "save-btn"),
                new ResolvedLocator("e2", LocatorStrategy.TEST_ID, "row-action"),
                new ResolvedLocator("e3", LocatorStrategy.ROLE_NAME, "Settings", "button")
        );

        CorrectiveFeedbackBuilder builder = new CorrectiveFeedbackBuilder();
        String feedback = builder.build(List.of(failedViolation), current);

        assertThat(feedback).contains("The following locators FAILED deterministic validation and MUST be revised");
        assertThat(feedback).contains("- element e2 (page.getByTestId(\"row-action\")): NOT_UNIQUE");
        assertThat(feedback).contains("matched 3 elements; must match exactly 1");
        assertThat(feedback).contains("<button id=\"r1\">");

        assertThat(feedback).contains("Already-passing locators (frozen, for context only):");
        assertThat(feedback).contains("e1 -> page.getByTestId(\"save-btn\")");
        assertThat(feedback).contains("e3 -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(\"Settings\").setExact(true))");

        // The failing element e2 must NOT be listed under already-passing locators
        String frozenSection = feedback.substring(feedback.indexOf("Already-passing locators"));
        assertThat(frozenSection).doesNotContain("e2 ->");
    }
}
