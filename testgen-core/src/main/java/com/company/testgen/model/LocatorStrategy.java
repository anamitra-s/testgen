package com.company.testgen.model;

/**
 * Locator strategy conforming to the locator grammar (§6.2, §6.4).
 */
public enum LocatorStrategy {
    TEST_ID,        // page.getByTestId("...")
    ROLE_NAME,      // page.getByRole(ROLE, new GetByRoleOptions().setName("...").setExact(true))
    LABEL,          // page.getByLabel("...", new GetByLabelOptions().setExact(true))
    PLACEHOLDER,    // page.getByPlaceholder("...", ...setExact(true))
    TEXT            // page.getByText("...", ...setExact(true))   — last resort, low confidence
}
