package com.company.testgen.llm;

import com.company.testgen.model.GeneratedFile;
import com.company.testgen.model.GeneratedFileSet;
import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.phases.DefaultCheckstyleValidator;
import com.company.testgen.phases.LintPhase;
import com.company.testgen.phases.LintResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalMockLlmClientTest {

    @Test
    @DisplayName("LocalMockLlmClient deterministically derives TEST_ID and ROLE_NAME locators")
    void testResolveLocatorsDeterministicStrategy() {
        LocalMockLlmClient client = new LocalMockLlmClient();

        String snapshotJson = """
                {
                  "interactions": [
                    {
                      "elementRefId": "e1",
                      "tagName": "button",
                      "role": "button",
                      "accessibleName": "Save",
                      "attributes": {
                        "data-testid": "save-button"
                      }
                    },
                    {
                      "elementRefId": "e2",
                      "tagName": "button",
                      "role": "button",
                      "accessibleName": "Cancel",
                      "attributes": {}
                    }
                  ]
                }
                """;

        ResolveRequest request = new ResolveRequest(
                UUID.randomUUID(),
                1L,
                0,
                "{}",
                snapshotJson,
                "{}",
                null
        );

        ResolvedLocatorSet locators = client.resolveLocators(request);

        assertThat(locators.locators()).hasSize(2);

        ResolvedLocator l1 = locators.locators().get(0);
        assertThat(l1.elementRefId()).isEqualTo("e1");
        assertThat(l1.strategy()).isEqualTo(LocatorStrategy.TEST_ID);
        assertThat(l1.value()).isEqualTo("save-button");
        assertThat(l1.expression()).contains("getByTestId(\"save-button\")");

        ResolvedLocator l2 = locators.locators().get(1);
        assertThat(l2.elementRefId()).isEqualTo("e2");
        assertThat(l2.strategy()).isEqualTo(LocatorStrategy.ROLE_NAME);
        assertThat(l2.value()).isEqualTo("Cancel");
        assertThat(l2.expression()).contains("getByRole");
    }

    @Test
    @DisplayName("LocalMockLlmClient respects -Dtestgen.mock.failFirstAttempt=true retry contract")
    void testFailFirstAttempt() {
        String prev = System.getProperty("testgen.mock.failFirstAttempt");
        try {
            System.setProperty("testgen.mock.failFirstAttempt", "true");
            LocalMockLlmClient client = new LocalMockLlmClient();

            String snapshotJson = """
                    {
                      "interactions": [
                        {
                          "elementRefId": "e1",
                          "tagName": "button",
                          "attributes": { "data-testid": "ok-btn" }
                        }
                      ]
                    }
                    """;

            // Attempt 0 -> non-unique locator
            ResolveRequest req0 = new ResolveRequest(UUID.randomUUID(), 1L, 0, "{}", snapshotJson, "{}", null);
            ResolvedLocatorSet locators0 = client.resolveLocators(req0);
            assertThat(locators0.locators()).hasSize(1);
            assertThat(locators0.locators().getFirst().value()).isEqualTo("mock-non-unique-locator-attempt-0");

            // Attempt 1 -> heals and returns valid locator
            ResolveRequest req1 = new ResolveRequest(UUID.randomUUID(), 1L, 1, "{}", snapshotJson, "{}", null);
            ResolvedLocatorSet locators1 = client.resolveLocators(req1);
            assertThat(locators1.locators()).hasSize(1);
            assertThat(locators1.locators().getFirst().value()).isEqualTo("ok-btn");

        } finally {
            if (prev != null) {
                System.setProperty("testgen.mock.failFirstAttempt", prev);
            } else {
                System.clearProperty("testgen.mock.failFirstAttempt");
            }
        }
    }

    @Test
    @DisplayName("LocalMockLlmClient generateCode produces valid Java/TestNG code passing LintPhase")
    void testGenerateCodePassesLint() {
        LocalMockLlmClient client = new LocalMockLlmClient();

        ParsedTestPlan plan = new ParsedTestPlan("dashboard", "Dashboard date test", List.of(), List.of(), false, false);
        ResolvedLocatorSet locators = ResolvedLocatorSet.of(
                new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "refresh-btn", null, "page.getByTestId(\"refresh-btn\")")
        );

        GenerateRequest request = new GenerateRequest(
                UUID.randomUUID(),
                1L,
                0,
                plan,
                locators,
                "{}",
                "main",
                null
        );

        GeneratedFileSet generated = client.generateCode(request);
        assertThat(generated.files()).isNotEmpty();

        GeneratedFile testFile = generated.files().getFirst();
        assertThat(testFile.content()).contains("page.getByTestId(\"refresh-btn\")");
        assertThat(testFile.content()).contains("org.testng.annotations.Test");

        // Validate that LintPhase passes
        LintPhase lintPhase = new LintPhase(new DefaultCheckstyleValidator());
        LintResult lintResult = lintPhase.check(generated, locators, plan);
        assertThat(lintResult.passed()).isTrue();
    }
}
