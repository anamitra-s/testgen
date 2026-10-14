package com.company.testgen.cli;

import com.company.testgen.cli.validation.ContractValidationException;
import com.company.testgen.cli.validation.ContractValidator;
import com.company.testgen.cli.validation.ValidationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractValidatorTest {

    private final ContractValidator validator = new ContractValidator(Duration.ofHours(24));

    @Test
    void testValidContractV2Passes(@TempDir Path temp) throws IOException {
        String json = """
                {
                  "contractVersion": 2,
                  "recorderVersion": "3.1.0",
                  "capturedAt": "%s",
                  "targetArea": "dashboard",
                  "baseUrl": "https://app.example.com",
                  "snapshots": [
                    {
                      "stateId": "s0",
                      "url": "https://app.example.com/dashboard",
                      "prunedHtml": "<main><button>Save</button></main>",
                      "prunedNodeCount": 15,
                      "truncated": false
                    }
                  ],
                  "interactions": [
                    {
                      "elementRefId": "e1",
                      "stateId": "s0",
                      "actionIndex": 0,
                      "tagName": "button",
                      "role": "button",
                      "accessibleName": "Save",
                      "fingerprint": {
                        "tagName": "button",
                        "role": "button",
                        "accessibleName": "Save",
                        "normalizedText": "Save",
                        "ordinalAmongRolePeers": 0,
                        "domPathHash": "hash1"
                      }
                    }
                  ]
                }
                """.formatted(Instant.now().minus(Duration.ofHours(2)).toString());

        String script = "await page.click('button'); // elementRefId: e1";

        Files.writeString(temp.resolve("recorded-elements.json"), json);
        Files.writeString(temp.resolve("recorded-test.js"), script);

        ValidationReport report = validator.validate(temp);
        assertThat(report.contractVersion()).isEqualTo(2);
        assertThat(report.interactionCount()).isEqualTo(1);
        assertThat(report.hasHtml()).isTrue();
    }

    @Test
    void testRejectsContractVersion1(@TempDir Path temp) throws IOException {
        String json = """
                {
                  "contractVersion": 1,
                  "capturedAt": "%s",
                  "snapshots": [{"stateId": "s0", "prunedHtml": "<div></div>"}],
                  "interactions": [{"elementRefId": "e1", "fingerprint": {"tagName":"button"}}]
                }
                """.formatted(Instant.now().toString());

        Files.writeString(temp.resolve("recorded-elements.json"), json);

        assertThatThrownBy(() -> validator.validate(temp))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("Recorder contract v2 required, found v1");
    }

    @Test
    void testRejectsEmptyInteractions(@TempDir Path temp) throws IOException {
        String json = """
                {
                  "contractVersion": 2,
                  "capturedAt": "%s",
                  "snapshots": [{"stateId": "s0", "prunedHtml": "<div></div>"}],
                  "interactions": []
                }
                """.formatted(Instant.now().toString());

        Files.writeString(temp.resolve("recorded-elements.json"), json);

        assertThatThrownBy(() -> validator.validate(temp))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("No interacted elements captured");
    }

    @Test
    void testRejectsMissingFingerprint(@TempDir Path temp) throws IOException {
        String json = """
                {
                  "contractVersion": 2,
                  "capturedAt": "%s",
                  "snapshots": [{"stateId": "s0", "prunedHtml": "<div></div>"}],
                  "interactions": [
                    {
                      "elementRefId": "e1",
                      "tagName": "button"
                    }
                  ]
                }
                """.formatted(Instant.now().toString());

        Files.writeString(temp.resolve("recorded-elements.json"), json);

        assertThatThrownBy(() -> validator.validate(temp))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("Contract v2 requires a fingerprint on every interaction");
    }

    @Test
    void testRejectsMissingPrunedHtml(@TempDir Path temp) throws IOException {
        String json = """
                {
                  "contractVersion": 2,
                  "capturedAt": "%s",
                  "snapshots": [
                    {
                      "stateId": "s0",
                      "url": "https://example.com"
                    }
                  ],
                  "interactions": [
                    {
                      "elementRefId": "e1",
                      "fingerprint": {
                        "tagName": "button",
                        "role": "button",
                        "accessibleName": "Save"
                      }
                    }
                  ]
                }
                """.formatted(Instant.now().toString());

        Files.writeString(temp.resolve("recorded-elements.json"), json);

        assertThatThrownBy(() -> validator.validate(temp))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("No page HTML captured");
    }

    @Test
    void testFreshnessGateRejectsOlderThan24Hours(@TempDir Path temp) throws IOException {
        Instant oldTimestamp = Instant.now().minus(Duration.ofHours(26));
        String json = """
                {
                  "contractVersion": 2,
                  "capturedAt": "%s",
                  "snapshots": [{"stateId": "s0", "prunedHtml": "<div>test</div>"}],
                  "interactions": [
                    {
                      "elementRefId": "e1",
                      "fingerprint": {
                        "tagName": "button",
                        "role": "button",
                        "accessibleName": "Save"
                      }
                    }
                  ]
                }
                """.formatted(oldTimestamp.toString());

        Files.writeString(temp.resolve("recorded-elements.json"), json);

        assertThatThrownBy(() -> validator.validate(temp))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("Recording is")
                .hasMessageContaining("old (limit 24h)");
    }

    @Test
    void testCrossFileRejectsUnknownElementRef(@TempDir Path temp) throws IOException {
        String json = """
                {
                  "contractVersion": 2,
                  "capturedAt": "%s",
                  "snapshots": [{"stateId": "s0", "prunedHtml": "<div>test</div>"}],
                  "interactions": [
                    {
                      "elementRefId": "e1",
                      "fingerprint": {
                        "tagName": "button",
                        "role": "button",
                        "accessibleName": "Save"
                      }
                    }
                  ]
                }
                """.formatted(Instant.now().toString());

        String script = "await page.click('button'); // elementRefId: e99";

        Files.writeString(temp.resolve("recorded-elements.json"), json);
        Files.writeString(temp.resolve("recorded-test.js"), script);

        assertThatThrownBy(() -> validator.validate(temp))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("Action 0 references unknown element e99");
    }
}
