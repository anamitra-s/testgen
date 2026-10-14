package com.company.testgen.observability;

import com.company.testgen.model.Phase;
import com.company.testgen.model.TestGenerationJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MdcTest {

    @BeforeEach
    @AfterEach
    void cleanUp() {
        MDC.clear();
    }

    @Test
    @DisplayName("Mdc.forJob sets all required MDC fields and clears on close")
    void testMdcForJobAndAutoClose() {
        UUID jobId = UUID.randomUUID();
        TestGenerationJob job = new TestGenerationJob(
                jobId,
                "v2:idemp-key-123",
                "checkout",
                "Verify checkout flow",
                "alice",
                "feature/rec-checkout",
                "hash-abc-789",
                "claude-sonnet-5",
                "1.0.0",
                "2.0.0",
                "sha-main-111",
                "worker-42",
                5L,
                Path.of("temp-dir")
        );

        try (var mdc = Mdc.forJob(job)) {
            assertThat(MDC.get("jobId")).isEqualTo(jobId.toString());
            assertThat(MDC.get("idempotencyKey")).isEqualTo("v2:idemp-key-123");
            assertThat(MDC.get("targetArea")).isEqualTo("checkout");
            assertThat(MDC.get("requester")).isEqualTo("alice");
            assertThat(MDC.get("leaseEpoch")).isEqualTo("5");
            assertThat(MDC.get("promptBundleHash")).isEqualTo("hash-abc-789");
            assertThat(MDC.get("modelId")).isEqualTo("claude-sonnet-5");

            // Test phase update
            Mdc.phase(Phase.RESOLVE, 1);
            assertThat(MDC.get("phase")).isEqualTo("RESOLVE");
            assertThat(MDC.get("attempt")).isEqualTo("1");
        }

        // Must be cleared upon close
        assertThat(MDC.get("jobId")).isNull();
        assertThat(MDC.get("idempotencyKey")).isNull();
        assertThat(MDC.get("targetArea")).isNull();
        assertThat(MDC.get("phase")).isNull();
    }
}
