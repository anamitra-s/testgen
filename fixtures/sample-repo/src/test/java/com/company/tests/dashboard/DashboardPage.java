package com.company.tests.dashboard;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * Dashboard page object representing the main dashboard area.
 */
public class DashboardPage {

    private final Page page;

    public DashboardPage(Page page) {
        this.page = page;
    }

    /**
     * Refreshes the dashboard data.
     */
    public void refresh() {
        page.getByTestId("refresh-button").click();
    }

    /**
     * Exports the dashboard report.
     */
    public void exportReport() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Export Report")).click();
    }

    public Locator getRefreshButton() {
        return page.getByTestId("refresh-button");
    }
}
