package com.company.testgen.phase;

import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.TestGenerationJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ParsePhaseTest {

    @Test
    @DisplayName("ParsePhase parses recorded-elements.json and recorded-test.js into ParsedTestPlan")
    void testParseWithScriptAndElements(@TempDir Path tempDir) throws IOException {
        String elementsJson = """
                {
                  "contractVersion": 2,
                  "recorderVersion": "3.1.0",
                  "capturedAt": "2026-10-14T10:00:00Z",
                  "targetArea": "dashboard",
                  "baseUrl": "https://app.example.com",
                  "snapshots": [
                    {
                      "stateId": "s0",
                      "url": "https://app.example.com/dashboard",
                      "prunedHtml": "<main id=\\"root\\"><button data-testid=\\"date-filter-trigger\\">Last 30 days</button></main>",
                      "prunedNodeCount": 2,
                      "truncated": false
                    }
                  ],
                  "interactions": [
                    {
                      "elementRefId": "e3",
                      "stateId": "s0",
                      "actionIndex": 0,
                      "tagName": "button",
                      "role": "button",
                      "accessibleName": "Last 30 days",
                      "attributes": {
                        "data-testid": "date-filter-trigger"
                      },
                      "fingerprint": {
                        "tagName": "button",
                        "role": "button",
                        "accessibleName": "Last 30 days",
                        "normalizedText": "Last 30 days",
                        "ordinalAmongRolePeers": 0,
                        "domPathHash": "sha256:abc"
                      }
                    }
                  ]
                }
                """;
        Files.writeString(tempDir.resolve("recorded-elements.json"), elementsJson);

        String testJs = """
                import { test, expect } from '@playwright/test';

                test('dashboard filter', async ({ page }) => {
                  await page.goto('https://app.example.com/dashboard');
                  await page.getByTestId('date-filter-trigger').click(); // ref: e3
                });
                """;
        Files.writeString(tempDir.resolve("recorded-test.js"), testJs);

        ParsePhase parsePhase = new ParsePhase();
        ParsedTestPlan plan = parsePhase.parse(tempDir, "dashboard", "Dashboard date filter test");

        assertThat(plan).isNotNull();
        assertThat(plan.targetArea()).isEqualTo("dashboard");
        assertThat(plan.testDescription()).isEqualTo("Dashboard date filter test");
        assertThat(plan.snapshots()).hasSize(1);
        assertThat(plan.snapshots().get(0).stateId()).isEqualTo("s0");
        assertThat(plan.snapshots().get(0).hasHtml()).isTrue();
        assertThat(plan.snapshots().get(0).truncated()).isFalse();

        assertThat(plan.actions()).isNotEmpty();
        assertThat(plan.actions().stream().anyMatch(a -> "e3".equals(a.elementRefId()))).isTrue();
        assertThat(plan.containsLoginFlow()).isFalse();
        assertThat(plan.mutatesData()).isFalse();
    }

    @Test
    @DisplayName("ParsePhase detects login flow from password field and login URL")
    void testDetectLoginFlow(@TempDir Path tempDir) throws IOException {
        String elementsJson = """
                {
                  "contractVersion": 2,
                  "capturedAt": "2026-10-14T10:00:00Z",
                  "targetArea": "auth",
                  "snapshots": [
                    {
                      "stateId": "s0",
                      "url": "https://app.example.com/login",
                      "prunedHtml": "<form><input type=\\"password\\" name=\\"pwd\\"/><button>Sign in</button></form>"
                    }
                  ],
                  "interactions": [
                    {
                      "elementRefId": "e1",
                      "stateId": "s0",
                      "tagName": "input",
                      "role": "textbox",
                      "attributes": { "type": "password" },
                      "fingerprint": {
                        "tagName": "input",
                        "role": "textbox",
                        "accessibleName": "",
                        "normalizedText": "",
                        "ordinalAmongRolePeers": 0,
                        "domPathHash": "sha256:123"
                      }
                    }
                  ]
                }
                """;
        Files.writeString(tempDir.resolve("recorded-elements.json"), elementsJson);

        ParsePhase phase = new ParsePhase();
        ParsedTestPlan plan = phase.parse(tempDir, "auth", "User login");

        assertThat(plan.containsLoginFlow()).isTrue();
    }

    @Test
    @DisplayName("ParsePhase detects data mutation from submit/delete buttons")
    void testDetectDataMutation(@TempDir Path tempDir) throws IOException {
        String elementsJson = """
                {
                  "contractVersion": 2,
                  "capturedAt": "2026-10-14T10:00:00Z",
                  "targetArea": "settings",
                  "snapshots": [
                    {
                      "stateId": "s0",
                      "url": "https://app.example.com/settings",
                      "prunedHtml": "<form><button data-testid=\\"delete-account-btn\\">Delete Account</button></form>"
                    }
                  ],
                  "interactions": [
                    {
                      "elementRefId": "e9",
                      "stateId": "s0",
                      "tagName": "button",
                      "role": "button",
                      "accessibleName": "Delete Account",
                      "attributes": { "data-testid": "delete-account-btn", "class": "btn btn-danger" },
                      "fingerprint": {
                        "tagName": "button",
                        "role": "button",
                        "accessibleName": "Delete Account",
                        "normalizedText": "Delete Account",
                        "ordinalAmongRolePeers": 0,
                        "domPathHash": "sha256:del"
                      }
                    }
                  ]
                }
                """;
        Files.writeString(tempDir.resolve("recorded-elements.json"), elementsJson);

        ParsePhase phase = new ParsePhase();
        ParsedTestPlan plan = phase.parse(tempDir, "settings", "Delete user account");

        assertThat(plan.mutatesData()).isTrue();
    }

    @Test
    @DisplayName("ParsePhase fallback generates actions from interactions when recorded-test.js is absent")
    void testFallbackActionsWhenScriptAbsent(@TempDir Path tempDir) throws IOException {
        String elementsJson = """
                {
                  "contractVersion": 2,
                  "capturedAt": "2026-10-14T10:00:00Z",
                  "targetArea": "profile",
                  "snapshots": [],
                  "interactions": [
                    {
                      "elementRefId": "ref-1",
                      "stateId": "s1",
                      "tagName": "input",
                      "textContent": "Jane Doe"
                    },
                    {
                      "elementRefId": "ref-2",
                      "stateId": "s1",
                      "tagName": "button",
                      "accessibleName": "Save profile"
                    }
                  ]
                }
                """;
        Files.writeString(tempDir.resolve("recorded-elements.json"), elementsJson);

        ParsePhase phase = new ParsePhase();
        ParsedTestPlan plan = phase.parse(tempDir, "profile", "Update profile");

        assertThat(plan.actions()).hasSize(2);
        assertThat(plan.actions().get(0).type()).isEqualTo("fill");
        assertThat(plan.actions().get(0).value()).isEqualTo("Jane Doe");
        assertThat(plan.actions().get(1).type()).isEqualTo("click");
        assertThat(plan.mutatesData()).isTrue(); // "Save profile" is a mutation
    }

    @Test
    @DisplayName("Plural package alias com.company.testgen.phases.ParsePhase behaves identically")
    void testPluralAlias() {
        com.company.testgen.phases.ParsePhase pluralPhase = new com.company.testgen.phases.ParsePhase();
        assertThat(pluralPhase).isInstanceOf(com.company.testgen.phase.ParsePhase.class);
    }
}
