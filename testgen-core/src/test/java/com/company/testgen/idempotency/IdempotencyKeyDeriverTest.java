package com.company.testgen.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyKeyDeriverTest {

    @Test
    @DisplayName("IT-07: Byte-identical recording with different JSON key order produces same idempotency key (RFC 8785 invariance)")
    void testIdenticalKeyForDifferentJsonKeyOrder(@TempDir Path temp1, @TempDir Path temp2) throws IOException {
        String json1 = """
                {
                  "contractVersion": 2,
                  "targetArea": "dashboard",
                  "capturedAt": "2026-08-05T09:14:22Z",
                  "baseUrl": "https://app.example.com",
                  "snapshots": [{"stateId": "s0", "prunedHtml": "<div>test</div>"}],
                  "interactions": [{"elementRefId": "e1", "role": "button"}]
                }
                """;

        String json2 = """
                {
                  "interactions": [{"elementRefId": "e1", "role": "button"}],
                  "snapshots": [{"prunedHtml": "<div>test</div>", "stateId": "s0"}],
                  "baseUrl": "https://app.example.com",
                  "capturedAt": "2026-08-05T09:14:22Z",
                  "targetArea": "dashboard",
                  "contractVersion": 2
                }
                """;

        String script = "await page.click('button');";

        Files.writeString(temp1.resolve("recorded-elements.json"), json1);
        Files.writeString(temp1.resolve("recorded-test.js"), script);

        Files.writeString(temp2.resolve("recorded-elements.json"), json2);
        Files.writeString(temp2.resolve("recorded-test.js"), script);

        IdempotencyKeyDeriver deriver = new IdempotencyKeyDeriver();
        String key1 = deriver.derive(temp1, "dashboard", "Dashboard click test");
        String key2 = deriver.derive(temp2, "dashboard", "Dashboard click test");

        assertThat(key1).startsWith("v2:");
        assertThat(key1).hasSize(67); // "v2:" + 64 hex characters
        assertThat(key1).isEqualTo(key2);
    }

    @Test
    @DisplayName("IT-08: CRLF vs LF line endings in recorded-test.js produce same idempotency key")
    void testIdenticalKeyForDifferentLineEndings(@TempDir Path temp1, @TempDir Path temp2) throws IOException {
        String json = """
                {
                  "contractVersion": 2,
                  "snapshots": [{"stateId": "s0", "prunedHtml": "<p>hi</p>"}],
                  "interactions": []
                }
                """;

        String scriptCrLf = "line1();\r\nline2();\r\n";
        String scriptLf = "line1();\nline2();\n";

        Files.writeString(temp1.resolve("recorded-elements.json"), json);
        Files.writeString(temp1.resolve("recorded-test.js"), scriptCrLf);

        Files.writeString(temp2.resolve("recorded-elements.json"), json);
        Files.writeString(temp2.resolve("recorded-test.js"), scriptLf);

        IdempotencyKeyDeriver deriver = new IdempotencyKeyDeriver();
        String key1 = deriver.derive(temp1, "dashboard", "Line ending test");
        String key2 = deriver.derive(temp2, "dashboard", "Line ending test");

        assertThat(key1).isEqualTo(key2);
    }

    @Test
    @DisplayName("IT-09: Recording differing by one attribute produces different key -> new job")
    void testDifferentAttributesProduceDifferentKey(@TempDir Path temp1, @TempDir Path temp2) throws IOException {
        String json1 = """
                {
                  "contractVersion": 2,
                  "targetArea": "dashboard",
                  "snapshots": [{"stateId": "s0", "prunedHtml": "<button id='1'>b</button>"}],
                  "interactions": []
                }
                """;

        String json2 = """
                {
                  "contractVersion": 2,
                  "targetArea": "dashboard",
                  "snapshots": [{"stateId": "s0", "prunedHtml": "<button id='2'>b</button>"}],
                  "interactions": []
                }
                """;

        String script = "test();";

        Files.writeString(temp1.resolve("recorded-elements.json"), json1);
        Files.writeString(temp1.resolve("recorded-test.js"), script);

        Files.writeString(temp2.resolve("recorded-elements.json"), json2);
        Files.writeString(temp2.resolve("recorded-test.js"), script);

        IdempotencyKeyDeriver deriver = new IdempotencyKeyDeriver();
        String key1 = deriver.derive(temp1, "dashboard", "Diff test");
        String key2 = deriver.derive(temp2, "dashboard", "Diff test");

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("capturedAt is excluded from key derivation so rerun timestamp difference does not change key")
    void testCapturedAtExcludedFromKeyDerivation(@TempDir Path temp1, @TempDir Path temp2) throws IOException {
        String jsonTime1 = """
                {
                  "contractVersion": 2,
                  "capturedAt": "2026-08-01T12:00:00Z",
                  "snapshots": [{"stateId": "s0", "prunedHtml": "<p>hi</p>"}],
                  "interactions": []
                }
                """;

        String jsonTime2 = """
                {
                  "contractVersion": 2,
                  "capturedAt": "2026-10-14T08:00:00Z",
                  "snapshots": [{"stateId": "s0", "prunedHtml": "<p>hi</p>"}],
                  "interactions": []
                }
                """;

        String script = "test();";

        Files.writeString(temp1.resolve("recorded-elements.json"), jsonTime1);
        Files.writeString(temp1.resolve("recorded-test.js"), script);

        Files.writeString(temp2.resolve("recorded-elements.json"), jsonTime2);
        Files.writeString(temp2.resolve("recorded-test.js"), script);

        IdempotencyKeyDeriver deriver = new IdempotencyKeyDeriver();
        String key1 = deriver.derive(temp1, "dashboard", "Exclusion test");
        String key2 = deriver.derive(temp2, "dashboard", "Exclusion test");

        assertThat(key1).isEqualTo(key2);
    }
}
