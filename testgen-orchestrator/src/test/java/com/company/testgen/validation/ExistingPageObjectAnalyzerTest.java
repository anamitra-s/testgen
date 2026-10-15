package com.company.testgen.validation;

import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.MethodDigest;
import com.company.testgen.model.PageObjectDigest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExistingPageObjectAnalyzerTest {

    @Test
    @DisplayName("ExistingPageObjectAnalyzer parses *Page.java ASTs, extracting methods, Javadoc, locators, and fields")
    void testAnalyzePageObjects(@TempDir Path tempDir) throws IOException {
        Path pagesDir = tempDir.resolve("pages/billing");
        Files.createDirectories(pagesDir);

        String pageSource = """
                package com.company.testgen.pages.billing;

                import com.microsoft.playwright.Page;
                import com.microsoft.playwright.Locator;

                public class BillingPage {
                    private final Page page;
                    private final Locator saveBtn = page.getByTestId("save-billing-btn");
                    private final Locator cancelBtn = page.locator("#cancel-btn");

                    public BillingPage(Page page) {
                        this.page = page;
                    }

                    /**
                     * Submits the updated billing payment method.
                     */
                    public void submitPaymentMethod(String cardNumber) {
                        page.getByTestId("card-input").fill(cardNumber);
                        page.getByRole("button", new Page.GetByRoleOptions().setName("Save")).click();
                        saveBtn.click();
                    }

                    // Private helper - should not be included in digest
                    private void internalValidation() {
                        page.locator(".error-banner");
                    }
                }
                """;
        Files.writeString(pagesDir.resolve("BillingPage.java"), pageSource);

        ExistingPageObjectAnalyzer analyzer = new ExistingPageObjectAnalyzer();
        ExistingCodeContext context = analyzer.analyze(pagesDir, "git-tree-sha-123", "billing");

        assertThat(context).isNotNull();
        assertThat(context.targetArea()).isEqualTo("billing");
        assertThat(context.pageObjects()).hasSize(1);

        PageObjectDigest digest = context.pageObjects().get(0);
        assertThat(digest.className()).isEqualTo("BillingPage");
        assertThat(digest.relativePath()).contains("BillingPage.java");

        // Public methods only (MethodDeclaration excludes constructors)
        assertThat(digest.methods()).hasSize(1);
        MethodDigest submitMethod = digest.methods().get(0);

        assertThat(submitMethod.name()).isEqualTo("submitPaymentMethod");
        assertThat(submitMethod.signature()).contains("submitPaymentMethod(String)");
        assertThat(submitMethod.javadoc()).contains("Submits the updated billing payment method.");
        assertThat(submitMethod.locatorLiterals()).contains("card-input", "button", "Save");

        // Field initializers
        assertThat(digest.locators()).hasSize(2);
        assertThat(digest.locators()).anyMatch(l -> "saveBtn".equals(l.fieldName()) && l.initializer().contains("save-billing-btn"));
        assertThat(digest.locators()).anyMatch(l -> "cancelBtn".equals(l.fieldName()) && l.initializer().contains("#cancel-btn"));
    }

    @Test
    @DisplayName("ExistingPageObjectAnalyzer caches result by sha256(gitTreeSha + analyzerVersion)")
    void testIntraJobCaffeineCache(@TempDir Path tempDir) throws IOException {
        Path pagesDir = tempDir.resolve("pages");
        Files.createDirectories(pagesDir);
        Files.writeString(pagesDir.resolve("DashboardPage.java"), """
                public class DashboardPage {
                    public void clickRefresh() {}
                }
                """);

        ExistingPageObjectAnalyzer analyzer = new ExistingPageObjectAnalyzer("v1.0.0");
        String treeSha = "sha-tree-abc";

        ExistingCodeContext first = analyzer.analyze(pagesDir, treeSha, "dashboard");
        assertThat(first.pageObjects()).hasSize(1);
        assertThat(analyzer.cache().estimatedSize()).isEqualTo(1);

        // Delete the file to prove the second call serves from cache without hitting filesystem
        Files.delete(pagesDir.resolve("DashboardPage.java"));

        ExistingCodeContext second = analyzer.analyze(pagesDir, treeSha, "dashboard");
        assertThat(second).isSameAs(first);
        assertThat(second.pageObjects()).hasSize(1);

        // Different treeSha should trigger fresh lookup (which now finds 0 files)
        ExistingCodeContext third = analyzer.analyze(pagesDir, "sha-tree-different", "dashboard");
        assertThat(third).isNotSameAs(first);
        assertThat(third.pageObjects()).isEmpty();
    }
}
