package com.company.testgen.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Root configuration model and loader for TestGen (§6.1, §9.1).
 * Resolution order: CLI flags -> environment variables -> application.yaml -> defaults.
 * Secrets are ONLY loaded from environment variables or CLI flags, never written into files.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PipelineProperties(
        IngestionProperties ingestion,
        LlmProperties llm,
        BudgetProperties budget,
        RetryProperties retry,
        DomProperties dom,
        RedactionProperties redaction,
        ValidationProperties validation,
        GitLabProperties gitlab,
        DbProperties db,
        IdempotencyProperties idempotency,
        EvalProperties eval,
        RetentionProperties retention,
        SessionProperties session
) {
    private static final Logger log = LoggerFactory.getLogger(PipelineProperties.class);
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");
    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)\\s*(ms|s|m|h|d)?$");

    public record IngestionProperties(
            Duration maxAge,
            int requiredContractVersion
    ) {
        public static IngestionProperties defaults() {
            return new IngestionProperties(Duration.ofHours(24), 2);
        }
    }

    public record LlmProperties(
            String provider,
            String modelId,
            String candidateModelId,
            int canaryPercent,
            String apiKey,
            int maxOutputTokens,
            double temperature,
            Duration timeout,
            int transportRetries,
            Duration transportBackoffBase,
            boolean cacheSystemMessages,
            boolean cacheTools
    ) {
        public static LlmProperties defaults(String apiKey) {
            return new LlmProperties(
                    "anthropic",
                    "claude-sonnet-5",
                    "",
                    0,
                    apiKey,
                    8192,
                    0.0,
                    Duration.ofSeconds(120),
                    3,
                    Duration.ofSeconds(2),
                    true,
                    false
            );
        }
    }

    public record ModelPrice(
            BigDecimal input,
            BigDecimal output,
            BigDecimal cacheWrite,
            BigDecimal cacheRead
    ) {
        public static final ModelPrice DEFAULT_SONNET = new ModelPrice(
                new BigDecimal("3.00"),
                new BigDecimal("15.00"),
                new BigDecimal("3.75"),
                new BigDecimal("0.30")
        );
    }

    public record BudgetProperties(
            int maxTokensPerJob,
            BigDecimal maxUsdPerJob,
            Map<String, ModelPrice> priceBook
    ) {
        public static BudgetProperties defaults() {
            return new BudgetProperties(
                    250_000,
                    new BigDecimal("2.50"),
                    Map.of("claude-sonnet-5", ModelPrice.DEFAULT_SONNET)
            );
        }
    }

    public record RetryProperties(
            int maxAttempts
    ) {
        public static RetryProperties defaults() {
            return new RetryProperties(3);
        }
    }

    public record DomProperties(
            int maxSnapshotTokens,
            int maxNodes
    ) {
        public static DomProperties defaults() {
            return new DomProperties(25000, 2000);
        }
    }

    public record RedactionProperties(
            String rulesVersion,
            boolean failClosed
    ) {
        public static RedactionProperties defaults() {
            return new RedactionProperties("redaction-v3", true);
        }
    }

    public record ValidationProperties(
            boolean l2Enabled,
            boolean l2Blocking,
            Duration l2Timeout,
            boolean allowTextStrategy
    ) {
        public static ValidationProperties defaults() {
            return new ValidationProperties(true, false, Duration.ofMinutes(5), true);
        }
    }

    public record GitLabProperties(
            String url,
            String projectId,
            String token,
            String targetBranch,
            String branchPrefix
    ) {
        public static GitLabProperties defaults() {
            return new GitLabProperties(
                    "https://gitlab.example.com",
                    "1842",
                    null,
                    "main",
                    "testgen"
            );
        }
    }

    public record DbProperties(
            String url,
            String user,
            String password,
            int poolSize,
            boolean migrateOnStart
    ) {
        public static DbProperties defaults() {
            return new DbProperties(
                    "jdbc:postgresql://localhost:5432/testgen",
                    "testgen",
                    "testgen",
                    4,
                    true
            );
        }
    }

    public record IdempotencyProperties(
            Duration leaseTtl,
            Duration heartbeatInterval,
            int maxEffectAttempts,
            Duration reconcileGrace
    ) {
        public static IdempotencyProperties defaults() {
            return new IdempotencyProperties(
                    Duration.ofMinutes(30),
                    Duration.ofMinutes(10),
                    3,
                    Duration.ofMinutes(30)
            );
        }
    }

    public record EvalProperties(
            String corpusDir,
            int defaultRepeats,
            int concurrency,
            double nonInferiorityMargin,
            double longHorizonMargin,
            int bootstrapResamples
    ) {
        public static EvalProperties defaults() {
            return new EvalProperties(
                    "evals/cases",
                    3,
                    4,
                    0.05,
                    0.10,
                    10000
            );
        }
    }

    public record RetentionProperties(
            int debugDays
    ) {
        public static RetentionProperties defaults() {
            return new RetentionProperties(30);
        }
    }

    public record SessionProperties(
            String vaultPath,
            Duration maxAge
    ) {
        public static SessionProperties defaults() {
            return new SessionProperties("secret/testgen/sessions", Duration.ofHours(4));
        }
    }

    public static PipelineProperties defaults(String apiKey) {
        return new PipelineProperties(
                IngestionProperties.defaults(),
                LlmProperties.defaults(apiKey),
                BudgetProperties.defaults(),
                RetryProperties.defaults(),
                DomProperties.defaults(),
                RedactionProperties.defaults(),
                ValidationProperties.defaults(),
                GitLabProperties.defaults(),
                DbProperties.defaults(),
                IdempotencyProperties.defaults(),
                EvalProperties.defaults(),
                RetentionProperties.defaults(),
                SessionProperties.defaults()
        );
    }

    public static PipelineProperties load() {
        return load(Collections.emptyMap());
    }

    public static PipelineProperties load(Map<String, String> cliOverrides) {
        InputStream is = PipelineProperties.class.getResourceAsStream("/application.yaml");
        return load(is, System.getenv(), cliOverrides);
    }

    public static PipelineProperties load(InputStream yamlStream, Map<String, String> env, Map<String, String> cliOverrides) {
        Map<String, String> effectiveEnv = env != null ? env : System.getenv();
        Map<String, String> effectiveCli = cliOverrides != null ? cliOverrides : Collections.emptyMap();

        Map<String, Object> rawMap = new HashMap<>();
        if (yamlStream != null) {
            try {
                ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                JsonNode root = mapper.readTree(yamlStream);
                JsonNode testgen = root.path("testgen");
                if (!testgen.isMissingNode() && testgen.isObject()) {
                    rawMap = mapper.convertValue(testgen, Map.class);
                }
            } catch (Exception e) {
                log.warn("Failed to parse YAML stream, falling back to defaults", e);
            }
        }

        // 1. Ingestion
        Map<String, Object> ingMap = getSection(rawMap, "ingestion");
        Duration ingMaxAge = parseDuration(override("ingestion.max-age", getVal(ingMap, "max-age", "24h"), effectiveEnv, effectiveCli));
        int ingContractVer = Integer.parseInt(override("ingestion.required-contract-version", getVal(ingMap, "required-contract-version", "2"), effectiveEnv, effectiveCli));
        IngestionProperties ingestion = new IngestionProperties(ingMaxAge, ingContractVer);

        // 2. LLM
        Map<String, Object> llmMap = getSection(rawMap, "llm");
        String provider = override("llm.provider", getVal(llmMap, "provider", "anthropic"), effectiveEnv, effectiveCli);
        String modelId = override("llm.model-id", getVal(llmMap, "model-id", "claude-sonnet-5"), effectiveEnv, effectiveCli);
        String candidateModelId = override("llm.candidate-model-id", getVal(llmMap, "candidate-model-id", ""), effectiveEnv, effectiveCli);
        int canaryPercent = Integer.parseInt(override("llm.canary-percent", getVal(llmMap, "canary-percent", "0"), effectiveEnv, effectiveCli));
        String apiKey = resolveSecret("llm.api-key", "ANTHROPIC_API_KEY", getVal(llmMap, "api-key", null), effectiveEnv, effectiveCli);
        int maxOutputTokens = Integer.parseInt(override("llm.max-output-tokens", getVal(llmMap, "max-output-tokens", "8192"), effectiveEnv, effectiveCli));
        double temperature = Double.parseDouble(override("llm.temperature", getVal(llmMap, "temperature", "0.0"), effectiveEnv, effectiveCli));
        Duration timeout = parseDuration(override("llm.timeout", getVal(llmMap, "timeout", "120s"), effectiveEnv, effectiveCli));
        int transportRetries = Integer.parseInt(override("llm.transport-retries", getVal(llmMap, "transport-retries", "3"), effectiveEnv, effectiveCli));
        Duration transportBackoffBase = parseDuration(override("llm.transport-backoff-base", getVal(llmMap, "transport-backoff-base", "2s"), effectiveEnv, effectiveCli));
        boolean cacheSystemMessages = Boolean.parseBoolean(override("llm.cache-system-messages", getVal(llmMap, "cache-system-messages", "true"), effectiveEnv, effectiveCli));
        boolean cacheTools = Boolean.parseBoolean(override("llm.cache-tools", getVal(llmMap, "cache-tools", "false"), effectiveEnv, effectiveCli));
        LlmProperties llm = new LlmProperties(provider, modelId, candidateModelId, canaryPercent, apiKey, maxOutputTokens, temperature, timeout, transportRetries, transportBackoffBase, cacheSystemMessages, cacheTools);

        // 3. Budget
        Map<String, Object> budgetMap = getSection(rawMap, "budget");
        int maxTokensPerJob = Integer.parseInt(override("budget.max-tokens-per-job", getVal(budgetMap, "max-tokens-per-job", "250000"), effectiveEnv, effectiveCli));
        BigDecimal maxUsdPerJob = new BigDecimal(override("budget.max-usd-per-job", getVal(budgetMap, "max-usd-per-job", "2.50"), effectiveEnv, effectiveCli));
        Map<String, ModelPrice> priceBook = parsePriceBook(budgetMap.get("price-book"));
        BudgetProperties budget = new BudgetProperties(maxTokensPerJob, maxUsdPerJob, priceBook);

        // 4. Retry
        Map<String, Object> retryMap = getSection(rawMap, "retry");
        int maxAttempts = Integer.parseInt(override("retry.max-attempts", getVal(retryMap, "max-attempts", "3"), effectiveEnv, effectiveCli));
        RetryProperties retry = new RetryProperties(maxAttempts);

        // 5. DOM
        Map<String, Object> domMap = getSection(rawMap, "dom");
        int maxSnapshotTokens = Integer.parseInt(override("dom.max-snapshot-tokens", getVal(domMap, "max-snapshot-tokens", "25000"), effectiveEnv, effectiveCli));
        int maxNodes = Integer.parseInt(override("dom.max-nodes", getVal(domMap, "max-nodes", "2000"), effectiveEnv, effectiveCli));
        DomProperties dom = new DomProperties(maxSnapshotTokens, maxNodes);

        // 6. Redaction
        Map<String, Object> redMap = getSection(rawMap, "redaction");
        String rulesVersion = override("redaction.rules-version", getVal(redMap, "rules-version", "redaction-v3"), effectiveEnv, effectiveCli);
        boolean failClosed = Boolean.parseBoolean(override("redaction.fail-closed", getVal(redMap, "fail-closed", "true"), effectiveEnv, effectiveCli));
        RedactionProperties redaction = new RedactionProperties(rulesVersion, failClosed);

        // 7. Validation
        Map<String, Object> valMap = getSection(rawMap, "validation");
        boolean l2Enabled = Boolean.parseBoolean(override("validation.l2-enabled", getVal(valMap, "l2-enabled", "true"), effectiveEnv, effectiveCli));
        boolean l2Blocking = Boolean.parseBoolean(override("validation.l2-blocking", getVal(valMap, "l2-blocking", "false"), effectiveEnv, effectiveCli));
        Duration l2Timeout = parseDuration(override("validation.l2-timeout", getVal(valMap, "l2-timeout", "5m"), effectiveEnv, effectiveCli));
        boolean allowTextStrategy = Boolean.parseBoolean(override("validation.allow-text-strategy", getVal(valMap, "allow-text-strategy", "true"), effectiveEnv, effectiveCli));
        ValidationProperties validation = new ValidationProperties(l2Enabled, l2Blocking, l2Timeout, allowTextStrategy);

        // 8. GitLab
        Map<String, Object> glMap = getSection(rawMap, "gitlab");
        String glUrl = override("gitlab.url", getVal(glMap, "url", "https://gitlab.example.com"), effectiveEnv, effectiveCli);
        String glProjId = override("gitlab.project-id", getVal(glMap, "project-id", "1842"), effectiveEnv, effectiveCli);
        String glToken = resolveSecret("gitlab.token", "GITLAB_TOKEN", getVal(glMap, "token", null), effectiveEnv, effectiveCli);
        String glTargetBranch = override("gitlab.target-branch", getVal(glMap, "target-branch", "main"), effectiveEnv, effectiveCli);
        String glBranchPrefix = override("gitlab.branch-prefix", getVal(glMap, "branch-prefix", "testgen"), effectiveEnv, effectiveCli);
        GitLabProperties gitlab = new GitLabProperties(glUrl, glProjId, glToken, glTargetBranch, glBranchPrefix);

        // 9. DB
        Map<String, Object> dbMap = getSection(rawMap, "db");
        String dbUrl = resolveSecret("db.url", "TESTGEN_DB_URL", getVal(dbMap, "url", "jdbc:postgresql://localhost:5432/testgen"), effectiveEnv, effectiveCli);
        String dbUser = resolveSecret("db.user", "TESTGEN_DB_USER", getVal(dbMap, "user", "testgen"), effectiveEnv, effectiveCli);
        String dbPass = resolveSecret("db.password", "TESTGEN_DB_PASSWORD", getVal(dbMap, "password", "testgen"), effectiveEnv, effectiveCli);
        int dbPoolSize = Integer.parseInt(override("db.pool-size", getVal(dbMap, "pool-size", "4"), effectiveEnv, effectiveCli));
        boolean dbMigrate = Boolean.parseBoolean(override("db.migrate-on-start", getVal(dbMap, "migrate-on-start", "true"), effectiveEnv, effectiveCli));
        DbProperties db = new DbProperties(dbUrl, dbUser, dbPass, dbPoolSize, dbMigrate);

        // 10. Idempotency
        Map<String, Object> idempMap = getSection(rawMap, "idempotency");
        Duration leaseTtl = parseDuration(override("idempotency.lease-ttl", getVal(idempMap, "lease-ttl", "30m"), effectiveEnv, effectiveCli));
        Duration heartbeatInterval = parseDuration(override("idempotency.heartbeat-interval", getVal(idempMap, "heartbeat-interval", "10m"), effectiveEnv, effectiveCli));
        int maxEffectAttempts = Integer.parseInt(override("idempotency.max-effect-attempts", getVal(idempMap, "max-effect-attempts", "3"), effectiveEnv, effectiveCli));
        Duration reconcileGrace = parseDuration(override("idempotency.reconcile-grace", getVal(idempMap, "reconcile-grace", "30m"), effectiveEnv, effectiveCli));
        IdempotencyProperties idempotency = new IdempotencyProperties(leaseTtl, heartbeatInterval, maxEffectAttempts, reconcileGrace);

        // 11. Eval
        Map<String, Object> evalMap = getSection(rawMap, "eval");
        String corpusDir = override("eval.corpus-dir", getVal(evalMap, "corpus-dir", "evals/cases"), effectiveEnv, effectiveCli);
        int defaultRepeats = Integer.parseInt(override("eval.default-repeats", getVal(evalMap, "default-repeats", "3"), effectiveEnv, effectiveCli));
        int concurrency = Integer.parseInt(override("eval.concurrency", getVal(evalMap, "concurrency", "4"), effectiveEnv, effectiveCli));
        double nonInferiorityMargin = Double.parseDouble(override("eval.non-inferiority-margin", getVal(evalMap, "non-inferiority-margin", "0.05"), effectiveEnv, effectiveCli));
        double longHorizonMargin = Double.parseDouble(override("eval.long-horizon-margin", getVal(evalMap, "long-horizon-margin", "0.10"), effectiveEnv, effectiveCli));
        int bootstrapResamples = Integer.parseInt(override("eval.bootstrap-resamples", getVal(evalMap, "bootstrap-resamples", "10000"), effectiveEnv, effectiveCli));
        EvalProperties eval = new EvalProperties(corpusDir, defaultRepeats, concurrency, nonInferiorityMargin, longHorizonMargin, bootstrapResamples);

        // 12. Retention
        Map<String, Object> retMap = getSection(rawMap, "retention");
        int debugDays = Integer.parseInt(override("retention.debug-days", getVal(retMap, "debug-days", "30"), effectiveEnv, effectiveCli));
        RetentionProperties retention = new RetentionProperties(debugDays);

        // 13. Session
        Map<String, Object> sessMap = getSection(rawMap, "session");
        String vaultPath = override("session.vault-path", getVal(sessMap, "vault-path", "secret/testgen/sessions"), effectiveEnv, effectiveCli);
        Duration sessMaxAge = parseDuration(override("session.max-age", getVal(sessMap, "max-age", "4h"), effectiveEnv, effectiveCli));
        SessionProperties session = new SessionProperties(vaultPath, sessMaxAge);

        return new PipelineProperties(
                ingestion, llm, budget, retry, dom, redaction, validation, gitlab, db, idempotency, eval, retention, session
        );
    }

    private static Map<String, Object> getSection(Map<String, Object> root, String key) {
        if (root == null) return Collections.emptyMap();
        Object val = root.get(key);
        if (val instanceof Map<?, ?> m) {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) {
                    result.put(e.getKey().toString(), e.getValue());
                }
            }
            return result;
        }
        return Collections.emptyMap();
    }

    private static String getVal(Map<String, Object> map, String key, String defaultVal) {
        if (map == null) return defaultVal;
        Object val = map.get(key);
        return val != null ? val.toString().trim() : defaultVal;
    }

    private static String override(String propertyPath, String defaultValue, Map<String, String> env, Map<String, String> cli) {
        // 1. CLI flag (e.g., --llm.model-id or model-id)
        String cliVal = getCliValue(propertyPath, cli);
        if (cliVal != null && !cliVal.isBlank()) {
            return cliVal.trim();
        }

        // 2. Environment variable (e.g. TESTGEN_LLM_MODEL_ID)
        String envKey = "TESTGEN_" + propertyPath.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        String envVal = env.get(envKey);
        if (envVal != null && !envVal.isBlank()) {
            return envVal.trim();
        }

        // 3. Interpolate ${VAR} in defaultValue if present
        if (defaultValue != null) {
            Matcher matcher = ENV_PATTERN.matcher(defaultValue);
            if (matcher.matches()) {
                String varName = matcher.group(1);
                String resolved = env.get(varName);
                if (resolved != null && !resolved.isBlank()) {
                    return resolved.trim();
                }
            }
        }

        return defaultValue;
    }

    private static String resolveSecret(String propertyPath, String primaryEnvVar, String defaultYamlValue, Map<String, String> env, Map<String, String> cli) {
        // 1. CLI override
        String cliVal = getCliValue(propertyPath, cli);
        if (cliVal != null && !cliVal.isBlank()) {
            return cliVal.trim();
        }

        // 2. Primary env var (e.g. ANTHROPIC_API_KEY, GITLAB_TOKEN)
        if (primaryEnvVar != null && env.containsKey(primaryEnvVar)) {
            String val = env.get(primaryEnvVar);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }

        // 3. Namespaced env var (e.g. TESTGEN_LLM_API_KEY)
        String envKey = "TESTGEN_" + propertyPath.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        if (env.containsKey(envKey)) {
            String val = env.get(envKey);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }

        // 4. Interpolate from yaml default ${VAR}
        if (defaultYamlValue != null) {
            Matcher matcher = ENV_PATTERN.matcher(defaultYamlValue);
            if (matcher.matches()) {
                String varName = matcher.group(1);
                String resolved = env.get(varName);
                if (resolved != null && !resolved.isBlank()) {
                    return resolved.trim();
                }
                return null;
            }
            return defaultYamlValue;
        }

        return null;
    }

    private static String getCliValue(String propertyPath, Map<String, String> cli) {
        if (cli == null || cli.isEmpty()) return null;
        if (cli.containsKey(propertyPath)) return cli.get(propertyPath);
        if (cli.containsKey("--" + propertyPath)) return cli.get("--" + propertyPath);

        // Also check leaf name (e.g. "model-id" for "llm.model-id")
        String leaf = propertyPath.substring(propertyPath.lastIndexOf('.') + 1);
        if (cli.containsKey(leaf)) return cli.get(leaf);
        if (cli.containsKey("--" + leaf)) return cli.get("--" + leaf);

        return null;
    }

    public static Duration parseDuration(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ZERO;
        }
        String clean = value.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = DURATION_PATTERN.matcher(clean);
        if (!matcher.matches()) {
            try {
                return Duration.parse(value);
            } catch (Exception e) {
                return Duration.ofSeconds(Long.parseLong(clean));
            }
        }

        long amount = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2);
        if (unit == null || unit.equals("s")) {
            return Duration.ofSeconds(amount);
        }
        return switch (unit) {
            case "ms" -> Duration.ofMillis(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> Duration.ofSeconds(amount);
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ModelPrice> parsePriceBook(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of("claude-sonnet-5", ModelPrice.DEFAULT_SONNET);
        }
        Map<String, ModelPrice> book = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() instanceof Map<?, ?> priceMap) {
                String model = entry.getKey().toString();
                BigDecimal in = toBigDecimal(priceMap.get("input"), "3.00");
                BigDecimal out = toBigDecimal(priceMap.get("output"), "15.00");
                BigDecimal cw = toBigDecimal(priceMap.get("cache-write"), "3.75");
                BigDecimal cr = toBigDecimal(priceMap.get("cache-read"), "0.30");
                book.put(model, new ModelPrice(in, out, cw, cr));
            }
        }
        return book.isEmpty() ? Map.of("claude-sonnet-5", ModelPrice.DEFAULT_SONNET) : Collections.unmodifiableMap(book);
    }

    private static BigDecimal toBigDecimal(Object val, String defaultVal) {
        if (val == null) return new BigDecimal(defaultVal);
        return new BigDecimal(val.toString());
    }
}
