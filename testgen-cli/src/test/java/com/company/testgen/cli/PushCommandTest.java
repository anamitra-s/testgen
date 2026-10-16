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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PushCommandTest {

    @Test
    void testPushCommandSuccessfulExecution(@TempDir Path temp) throws IOException {
        String json = createValidRecordingJson();
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
    void testPushCommandDryRun(@TempDir Path temp) throws IOException {
        String json = createValidRecordingJson();
        String script = "await page.click('button'); // elementRefId: e1";

        Files.writeString(temp.resolve("recorded-elements.json"), json);
        Files.writeString(temp.resolve("recorded-test.js"), script);

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("push", temp.toString(), "--area=dashboard", "--desc=Dry run test", "--dry-run");

        assertThat(exitCode).isEqualTo(0);
        assertThat(out.toString()).contains("✓ Contract v2 valid");
        assertThat(out.toString()).contains("[dry-run]");
    }

    @Test
    void testPushCommandLocalMode(@TempDir Path temp) throws IOException {
        String json = createValidRecordingJson();
        String script = "await page.click('button'); // elementRefId: e1";

        Files.writeString(temp.resolve("recorded-elements.json"), json);
        Files.writeString(temp.resolve("recorded-test.js"), script);

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("push", temp.toString(), "--area=dashboard", "--desc=Local mode test", "--local", "--provider=gemini");

        assertThat(exitCode).isEqualTo(0);
        assertThat(out.toString()).contains("✓ Contract v2 valid");
        assertThat(out.toString()).contains("✓ Idempotency key: v2:");
        assertThat(out.toString()).contains("(local simulation)");
        assertThat(out.toString()).contains("Local mode: Jenkins trigger skipped");
    }

    @Test
    void testPushCommandWithCustomGitPusherAndJenkinsTrigger(@TempDir Path temp) throws IOException {
        String json = createValidRecordingJson();
        String script = "await page.click('button'); // elementRefId: e1";

        Files.writeString(temp.resolve("recorded-elements.json"), json);
        Files.writeString(temp.resolve("recorded-test.js"), script);

        AtomicBoolean gitPushed = new AtomicBoolean(false);
        AtomicReference<String> pushedBranch = new AtomicReference<>();
        GitPusher customPusher = (folder, branch, remoteUrl) -> {
            gitPushed.set(true);
            pushedBranch.set(branch);
        };

        AtomicBoolean jenkinsTriggered = new AtomicBoolean(false);
        AtomicReference<Map<String, String>> receivedParams = new AtomicReference<>();
        JenkinsTrigger customTrigger = (url, job, user, token, params) -> {
            jenkinsTriggered.set(true);
            receivedParams.set(params);
            return url + "/job/" + job + "/42";
        };

        PushCommand pushCommand = new PushCommand(customPusher, customTrigger);

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(pushCommand);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute(
                temp.toString(),
                "--area=composer",
                "--desc=Custom push test",
                "--git-url=https://gitlab.example.com/repo.git",
                "--jenkins-url=https://jenkins.example.com",
                "--jenkins-job=custom-job",
                "--jenkins-user=admin",
                "--jenkins-token=secret-token"
        );

        assertThat(exitCode).isEqualTo(0);
        assertThat(gitPushed.get()).isTrue();
        assertThat(pushedBranch.get()).startsWith("testgen-scratch/");
        assertThat(jenkinsTriggered.get()).isTrue();
        assertThat(receivedParams.get()).isNotNull();
        assertThat(receivedParams.get().get("TARGET_AREA")).isEqualTo("composer");
        assertThat(receivedParams.get().get("TEST_DESC")).isEqualTo("Custom push test");
        assertThat(receivedParams.get().get("SCRATCH_BRANCH")).isEqualTo(pushedBranch.get());
        assertThat(out.toString()).contains("✓ Triggered  https://jenkins.example.com/job/custom-job/42");
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

    private String createValidRecordingJson() {
        return """
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
    }
}
