package com.company.testgen.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PipelinePropertiesTest {

    @Test
    @DisplayName("Loads application.yaml from classpath and matches Section 9.1 defaults")
    void testLoadApplicationYamlDefaults() {
        PipelineProperties props = PipelineProperties.load();
        assertThat(props).isNotNull();

        // 1. Ingestion
        assertThat(props.ingestion().maxAge()).isEqualTo(Duration.ofHours(24));
        assertThat(props.ingestion().requiredContractVersion()).isEqualTo(2);

        // 2. LLM
        assertThat(props.llm().provider()).isEqualTo("anthropic");
        assertThat(props.llm().modelId()).isEqualTo("claude-sonnet-5");
        assertThat(props.llm().candidateModelId()).isEmpty();
        assertThat(props.llm().canaryPercent()).isEqualTo(0);
        assertThat(props.llm().maxOutputTokens()).isEqualTo(8192);
        assertThat(props.llm().temperature()).isEqualTo(0.0);
        assertThat(props.llm().timeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(props.llm().transportRetries()).isEqualTo(3);
        assertThat(props.llm().transportBackoffBase()).isEqualTo(Duration.ofSeconds(2));
        assertThat(props.llm().cacheSystemMessages()).isTrue();
        assertThat(props.llm().cacheTools()).isFalse();

        // 3. Budget
        assertThat(props.budget().maxTokensPerJob()).isEqualTo(250_000);
        assertThat(props.budget().maxUsdPerJob()).isEqualByComparingTo(new BigDecimal("2.50"));
        assertThat(props.budget().priceBook()).containsKey("claude-sonnet-5");
        PipelineProperties.ModelPrice price = props.budget().priceBook().get("claude-sonnet-5");
        assertThat(price.input()).isEqualByComparingTo(new BigDecimal("3.00"));
        assertThat(price.output()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(price.cacheWrite()).isEqualByComparingTo(new BigDecimal("3.75"));
        assertThat(price.cacheRead()).isEqualByComparingTo(new BigDecimal("0.30"));

        // 4. Retry
        assertThat(props.retry().maxAttempts()).isEqualTo(3);

        // 5. DOM
        assertThat(props.dom().maxSnapshotTokens()).isEqualTo(25_000);
        assertThat(props.dom().maxNodes()).isEqualTo(2_000);

        // 6. Redaction
        assertThat(props.redaction().rulesVersion()).isEqualTo("redaction-v3");
        assertThat(props.redaction().failClosed()).isTrue();

        // 7. Validation
        assertThat(props.validation().l2Enabled()).isTrue();
        assertThat(props.validation().l2Blocking()).isFalse();
        assertThat(props.validation().l2Timeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.validation().allowTextStrategy()).isTrue();

        // 8. GitLab
        assertThat(props.gitlab().url()).isEqualTo("https://gitlab.example.com");
        assertThat(props.gitlab().projectId()).isEqualTo("1842");
        assertThat(props.gitlab().targetBranch()).isEqualTo("main");
        assertThat(props.gitlab().branchPrefix()).isEqualTo("testgen");

        // 9. DB
        assertThat(props.db().poolSize()).isEqualTo(4);
        assertThat(props.db().migrateOnStart()).isTrue();

        // 10. Idempotency
        assertThat(props.idempotency().leaseTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.idempotency().heartbeatInterval()).isEqualTo(Duration.ofMinutes(10));
        assertThat(props.idempotency().maxEffectAttempts()).isEqualTo(3);
        assertThat(props.idempotency().reconcileGrace()).isEqualTo(Duration.ofMinutes(30));

        // 11. Eval
        assertThat(props.eval().corpusDir()).isEqualTo("evals/cases");
        assertThat(props.eval().defaultRepeats()).isEqualTo(3);
        assertThat(props.eval().concurrency()).isEqualTo(4);
        assertThat(props.eval().nonInferiorityMargin()).isEqualTo(0.05);
        assertThat(props.eval().longHorizonMargin()).isEqualTo(0.10);
        assertThat(props.eval().bootstrapResamples()).isEqualTo(10_000);

        // 12. Retention
        assertThat(props.retention().debugDays()).isEqualTo(30);

        // 13. Session
        assertThat(props.session().vaultPath()).isEqualTo("secret/testgen/sessions");
        assertThat(props.session().maxAge()).isEqualTo(Duration.ofHours(4));
    }

    @Test
    @DisplayName("Environment variables interpolate secrets and override configurations")
    void testEnvironmentVariableInterpolationAndOverrides() {
        String yaml = """
                testgen:
                  llm:
                    model-id: "claude-sonnet-5"
                    api-key: ${ANTHROPIC_API_KEY}
                  gitlab:
                    token: ${GITLAB_TOKEN}
                """;

        Map<String, String> env = Map.of(
                "ANTHROPIC_API_KEY", "sk-secret-123",
                "GITLAB_TOKEN", "gl-secret-456",
                "TESTGEN_LLM_MODEL_ID", "claude-3-opus-custom",
                "TESTGEN_RETRY_MAX_ATTEMPTS", "5"
        );

        PipelineProperties props = PipelineProperties.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                env,
                Map.of()
        );

        assertThat(props.llm().apiKey()).isEqualTo("sk-secret-123");
        assertThat(props.gitlab().token()).isEqualTo("gl-secret-456");
        assertThat(props.llm().modelId()).isEqualTo("claude-3-opus-custom");
        assertThat(props.retry().maxAttempts()).isEqualTo(5);
    }

    @Test
    @DisplayName("CLI flags take highest precedence over environment variables and YAML")
    void testCliFlagPrecedence() {
        String yaml = """
                testgen:
                  llm:
                    model-id: "claude-sonnet-5"
                  retry:
                    max-attempts: 3
                """;

        Map<String, String> env = Map.of(
                "TESTGEN_LLM_MODEL_ID", "model-from-env"
        );

        Map<String, String> cli = Map.of(
                "--model-id", "model-from-cli",
                "--max-attempts", "7"
        );

        PipelineProperties props = PipelineProperties.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                env,
                cli
        );

        assertThat(props.llm().modelId()).isEqualTo("model-from-cli");
        assertThat(props.retry().maxAttempts()).isEqualTo(7);
    }

    @Test
    @DisplayName("Duration parser handles units: ms, s, m, h, d")
    void testDurationParser() {
        assertThat(PipelineProperties.parseDuration("500ms")).isEqualTo(Duration.ofMillis(500));
        assertThat(PipelineProperties.parseDuration("120s")).isEqualTo(Duration.ofSeconds(120));
        assertThat(PipelineProperties.parseDuration("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(PipelineProperties.parseDuration("24h")).isEqualTo(Duration.ofHours(24));
        assertThat(PipelineProperties.parseDuration("7d")).isEqualTo(Duration.ofDays(7));
        assertThat(PipelineProperties.parseDuration("10")).isEqualTo(Duration.ofSeconds(10));
    }
}
