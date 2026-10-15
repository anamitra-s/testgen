package com.company.testgen.phases;

import com.company.testgen.model.GeneratedFile;
import com.company.testgen.model.GeneratedFileSet;
import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LintPhaseTest {

    private LintPhase lintPhase;
    private ResolvedLocatorSet validLocators;
    private ParsedTestPlan testPlan;

    @BeforeEach
    void setUp() {
        lintPhase = new LintPhase();
        validLocators = ResolvedLocatorSet.of(
                new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "submit-btn", null, "page.getByTestId(\"submit-btn\")")
        );
        testPlan = new ParsedTestPlan("billing", "Billing submission", List.of(), List.of(), false, false);
    }

    @Test
    @DisplayName("Check 1: JavaParser AST parse check catches malformed or truncated Java syntax")
    void testCheck1AstParseFailure() {
        GeneratedFile truncatedFile = new GeneratedFile(
                "src/test/java/com/company/tests/billing/BillingTest.java",
                """
                package com.company.tests.billing;
                import org.junit.jupiter.api.Test;
                public class BillingTest {
                    @Test
                    public void testIncomplete() {
                        int x = ; // syntax error
                """,
                true
        );

        LintResult result = lintPhase.check(new GeneratedFileSet(List.of(truncatedFile)), validLocators, "billing");
        assertThat(result.passed()).isFalse();
        assertThat(result.report()).contains("JavaParser syntax parse error");
    }

    @Test
    @DisplayName("Check 2: Locator substitution check verifies required expressions appear verbatim")
    void testCheck2MissingLocatorExpression() {
        GeneratedFile fileMissingLocator = new GeneratedFile(
                "src/test/java/com/company/tests/billing/BillingTest.java",
                """
                package com.company.tests.billing;
                import org.junit.jupiter.api.Test;
                public class BillingTest {
                    @Test
                    public void testAction() {
                        // The required locator is missing here
                    }
                }
                """,
                true
        );

        LintResult result = lintPhase.check(new GeneratedFileSet(List.of(fileMissingLocator)), validLocators, "billing");
        assertThat(result.passed()).isFalse();
        assertThat(result.report()).contains("Locator substitution error: required expression 'page.getByTestId(\"submit-btn\")'");
    }

    @Test
    @DisplayName("Check 2: Locator substitution check detects unvalidated locator expressions")
    void testCheck2UnvalidatedLocatorExpression() {
        GeneratedFile fileWithRogueLocator = new GeneratedFile(
                "src/test/java/com/company/tests/billing/BillingTest.java",
                """
                package com.company.tests.billing;
                import org.junit.jupiter.api.Test;
                public class BillingTest {
                    @Test
                    public void testAction() {
                        page.getByTestId("submit-btn");
                        page.locator("//button[@id='rogue']");
                    }
                }
                """,
                true
        );

        LintResult result = lintPhase.check(new GeneratedFileSet(List.of(fileWithRogueLocator)), validLocators, "billing");
        assertThat(result.passed()).isFalse();
        assertThat(result.report()).contains("Unvalidated locator expression found");
        assertThat(result.report()).contains("page.locator(\"//button[@id='rogue']\")");
    }

    @Test
    @DisplayName("Check 3: Checkstyle validation catches wildcard imports")
    void testCheck3WildcardImports() {
        GeneratedFile fileWithWildcard = new GeneratedFile(
                "src/test/java/com/company/tests/billing/BillingTest.java",
                """
                package com.company.tests.billing;
                import org.junit.jupiter.api.*;
                public class BillingTest {
                    @Test
                    public void testAction() {
                        page.getByTestId("submit-btn");
                    }
                }
                """,
                true
        );

        LintResult result = lintPhase.check(new GeneratedFileSet(List.of(fileWithWildcard)), validLocators, "billing");
        assertThat(result.passed()).isFalse();
        assertThat(result.report()).contains("AvoidStarImport");
    }

    @Test
    @DisplayName("Check 3: Checkstyle validation catches Thread.sleep, System.out, and missing block braces")
    void testCheck3ForbiddenConstructs() {
        GeneratedFile badFile = new GeneratedFile(
                "src/test/java/com/company/tests/billing/BillingTest.java",
                """
                package com.company.tests.billing;
                import org.junit.jupiter.api.Test;
                public class BillingTest {
                    @Test
                    public void testAction() {
                        page.getByTestId("submit-btn");
                        System.out.println("debug");
                        Thread.sleep(1000);
                        if (true) return;
                    }
                }
                """,
                true
        );

        LintResult result = lintPhase.check(new GeneratedFileSet(List.of(badFile)), validLocators, "billing");
        assertThat(result.passed()).isFalse();
        assertThat(result.report()).contains("NoSystemOut");
        assertThat(result.report()).contains("NoThreadSleep");
        assertThat(result.report()).contains("NeedBraces");
    }

    @Test
    @DisplayName("Check 4: Package and import sanity check catches banned Selenium imports and invalid package")
    void testCheck4PackageAndImportSanity() {
        GeneratedFile badPackageFile = new GeneratedFile(
                "src/test/java/com/company/tests/billing/BillingTest.java",
                """
                package com.other.billing;
                import org.junit.jupiter.api.Test;
                import org.openqa.selenium.WebDriver;
                public class BillingTest {
                    @Test
                    public void testAction() {
                        page.getByTestId("submit-btn");
                    }
                }
                """,
                true
        );

        LintResult result = lintPhase.check(new GeneratedFileSet(List.of(badPackageFile)), validLocators, "billing");
        assertThat(result.passed()).isFalse();
        assertThat(result.report()).contains("must start with 'com.company.tests'");
        assertThat(result.report()).contains("Banned Selenium import");
    }

    @Test
    @DisplayName("Check 4: Page objects must start with com.company.pages and import Playwright")
    void testCheck4PageObjectSanity() {
        GeneratedFile pageMissingPlaywright = new GeneratedFile(
                "src/main/java/com/company/pages/billing/BillingPage.java",
                """
                package com.company.pages.billing;
                public class BillingPage {
                    public void submit() {
                        page.getByTestId("submit-btn");
                    }
                }
                """,
                true
        );

        LintResult result = lintPhase.check(new GeneratedFileSet(List.of(pageMissingPlaywright)), validLocators, "billing");
        assertThat(result.passed()).isFalse();
        assertThat(result.report()).contains("does not import Playwright (com.microsoft.playwright.*)");
    }

    @Test
    @DisplayName("Valid test and page object pass all four checks successfully")
    void testAllChecksPass() {
        GeneratedFile testFile = new GeneratedFile(
                "src/test/java/com/company/tests/billing/BillingTest.java",
                """
                package com.company.tests.billing;
                import org.junit.jupiter.api.Test;
                public class BillingTest {
                    @Test
                    public void testSubmit() {
                        page.getByTestId("submit-btn");
                    }
                }
                """,
                true
        );

        GeneratedFile pageFile = new GeneratedFile(
                "src/main/java/com/company/pages/billing/BillingPage.java",
                """
                package com.company.pages.billing;
                import com.microsoft.playwright.Page;
                public class BillingPage {
                    private final Page page;
                    public BillingPage(Page page) {
                        this.page = page;
                    }
                }
                """,
                true
        );

        LintResult result = lintPhase.check(new GeneratedFileSet(List.of(testFile, pageFile)), validLocators, "billing");
        assertThat(result.passed()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.report()).isEqualTo("Lint checks passed successfully.");
    }
}
