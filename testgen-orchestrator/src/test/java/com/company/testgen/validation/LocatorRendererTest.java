package com.company.testgen.validation;

import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.ResolvedLocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocatorRendererTest {

    @Test
    @DisplayName("LocatorRenderer renders all 5 locator strategies into exact Playwright Java expressions")
    void testRenderStrategies() {
        ResolvedLocator testId = new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "submit-btn");
        assertThat(LocatorRenderer.render(testId))
                .isEqualTo("page.getByTestId(\"submit-btn\")");

        ResolvedLocator roleName = new ResolvedLocator("e2", LocatorStrategy.ROLE_NAME, "Save Profile", "button");
        assertThat(LocatorRenderer.render(roleName))
                .isEqualTo("page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(\"Save Profile\").setExact(true))");

        ResolvedLocator label = new ResolvedLocator("e3", LocatorStrategy.LABEL, "Email address");
        assertThat(LocatorRenderer.render(label))
                .isEqualTo("page.getByLabel(\"Email address\", new Page.GetByLabelOptions().setExact(true))");

        ResolvedLocator placeholder = new ResolvedLocator("e4", LocatorStrategy.PLACEHOLDER, "Enter your search");
        assertThat(LocatorRenderer.render(placeholder))
                .isEqualTo("page.getByPlaceholder(\"Enter your search\", new Page.GetByPlaceholderOptions().setExact(true))");

        ResolvedLocator text = new ResolvedLocator("e5", LocatorStrategy.TEXT, "Confirm payment");
        assertThat(LocatorRenderer.render(text))
                .isEqualTo("page.getByText(\"Confirm payment\", new Page.GetByTextOptions().setExact(true))");
    }

    @Test
    @DisplayName("LocatorRenderer properly escapes quotes and backslashes")
    void testRenderEscapes() {
        ResolvedLocator loc = new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "btn-\"hello\"\\world");
        assertThat(LocatorRenderer.render(loc))
                .isEqualTo("page.getByTestId(\"btn-\\\"hello\\\"\\\\world\")");
    }

    @Test
    @DisplayName("LocatorRenderer handles empty/null role by falling back to UNKNOWN and validates null input")
    void testRenderRoleFallbackAndNulls() {
        ResolvedLocator nullRole = new ResolvedLocator("e1", LocatorStrategy.ROLE_NAME, "Search", null);
        assertThat(LocatorRenderer.render(nullRole))
                .contains("AriaRole.UNKNOWN");

        ResolvedLocator blankRole = new ResolvedLocator("e1", LocatorStrategy.ROLE_NAME, "Search", "  ");
        assertThat(LocatorRenderer.render(blankRole))
                .contains("AriaRole.UNKNOWN");

        assertThatThrownBy(() -> LocatorRenderer.render(null))
                .isInstanceOf(NullPointerException.class);

        ResolvedLocator nullStrategy = new ResolvedLocator("e1", null, "val");
        assertThatThrownBy(() -> LocatorRenderer.render(nullStrategy))
                .isInstanceOf(NullPointerException.class);
    }
}
