package com.company.testgen.observability;

import com.company.testgen.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobSummaryWriterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("JobSummaryWriter writes valid job-summary.json conforming to §9.3 schema")
    void testWriteJobSummary() throws IOException {
        Path targetFile = tempDir.resolve("custom-summary.json");
        JobSummaryWriter writer = new JobSummaryWriter(targetFile);

        JobSummary summary = new JobSummary(
                "8f2a-test-id",
                "v2:a3f9-idemp-key",
                "DONE",
                "VERIFIED",
                "dashboard",
                1,
                false,
                null,
                List.of(new JobSummary.LocatorSummary("e3", "TEST_ID", "date-filter-trigger", "PASS", "PASS")),
                new JobSummary.VerificationSummary("PASSED", 41200L),
                new JobSummary.CostSummary(32400, 18100, 3900, new BigDecimal("0.14")),
                new JobSummary.ProvenanceSummary("claude-sonnet-5", "a91f-hash", "1.4.2", "7c2e91a"),
                "https://gitlab.example.com/merge_requests/2291",
                252000L
        );

        Path writtenPath = writer.write(summary);
        assertThat(writtenPath).exists();

        String jsonContent = Files.readString(writtenPath);
        assertThat(jsonContent).contains("\"jobId\" : \"8f2a-test-id\"");
        assertThat(jsonContent).contains("\"confidenceTier\" : \"VERIFIED\"");
        assertThat(jsonContent).contains("\"date-filter-trigger\"");

        JobSummary deserialized = Json.parse(jsonContent, JobSummary.class);
        assertThat(deserialized.jobId()).isEqualTo("8f2a-test-id");
        assertThat(deserialized.targetArea()).isEqualTo("dashboard");
        assertThat(deserialized.cost().inputTokens()).isEqualTo(32400);
        assertThat(deserialized.cost().usd()).isEqualByComparingTo(new BigDecimal("0.14"));
    }
}
