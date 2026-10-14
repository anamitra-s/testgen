package com.company.testgen.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PushCommandTest {

    @Test
    void testPushCommandSuccessfulExecution(@TempDir Path temp) throws IOException {
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
                      "prunedHtml": "<main><button>Submit</button></main>",
                      "prunedNodeCount": 10,
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
                      "accessibleName": "Submit",
                      "fingerprint": {
                        "tagName": "button",
                        "role": "button",
                        "accessibleName": "Submit",
                        "normalizedText": "Submit",
                        "ordinalAmongRolePeers": 0,
                        "domPathHash": "hash1"
                      }
                    }
                  ]
                }
                """.formatted(Instant.now().toString());

        String script = "await page.click('button'); // elementRefId: e1";

        Files.writeString(temp.resolve("recorded-elements.json"), json);
        Files.writeString(temp.resolve("recorded-test.js"), script);

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("push", temp.toString(), "--area=dashboard", "--desc=Dashboard submit test");

        assertThat(exitCode).isEqualTo(0);
        assertThat(out.toString()).contains("✓ Contract v2 valid");
        assertThat(out.toString()).contains("✓ Idempotency key: v2:");
    }

    @Test
    void testPushCommandFailsOnInvalidRecording(@TempDir Path temp) throws IOException {
        String json = """
                {
                  "contractVersion": 1,
                  "capturedAt": "%s",
                  "snapshots": [],
                  "interactions": []
                }
                """.formatted(Instant.now().toString());

        Files.writeString(temp.resolve("recorded-elements.json"), json);
        Files.writeString(temp.resolve("recorded-test.js"), "test();");

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("push", temp.toString(), "--area=dashboard", "--desc=Invalid test");

        assertThat(exitCode).isEqualTo(30); // Exit code 30: INVALID_RECORDING
        assertThat(err.toString()).contains("✗ Ingestion validation failed");
    }
}
