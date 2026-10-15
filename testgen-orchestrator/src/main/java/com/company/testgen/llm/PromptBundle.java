package com.company.testgen.llm;

import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.ResolvedLocatorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Encapsulates prompt templates for Resolve and Generate phases (§6.1, §6.4, §6.10, §6.11, §6.12).
 * Loads prompts/vN files from filesystem or classpath and computes a deterministic SHA-256 bundle hash for provenance audit (FR-31, FR-33).
 */
public class PromptBundle {

    private static final Logger log = LoggerFactory.getLogger(PromptBundle.class);

    public static final String DEFAULT_VERSION = "v7";

    public static final String DEFAULT_RESOLVE_SYSTEM = """
            # Resolve System Prompt (v7)

            You are an expert Playwright-Java test automation engineer responsible for the Resolve phase (§6.4, §6.10).
            Your goal is to determine the most deterministic, resilient locator strategy for each recorded DOM element interaction.

            ## Allowed Locator Productions (§6.4)
            1. TEST_ID: page.getByTestId("value")
            2. ROLE_NAME: page.getByRole(AriaRole.<ROLE>, new Page.GetByRoleOptions().setName("value").setExact(true))
            3. LABEL: page.getByLabel("value", new Page.GetByLabelOptions().setExact(true))
            4. PLACEHOLDER: page.getByPlaceholder("value", new Page.GetByPlaceholderOptions().setExact(true))
            5. TEXT: page.getByText("value", new Page.GetByTextOptions().setExact(true)) (last resort)

            ## Banned Productions & Patterns
            - BANNED: XPath selectors (xpath=, //, ..).
            - BANNED: Raw CSS selectors.
            - BANNED: Structural pseudo-classes (:nth-child, :nth-of-type, :first-child).
            - BANNED: Chained selectors using >>.
            - BANNED: Index-based disambiguation .nth(i).
            - BANNED: Regex name matching.
            - BANNED: Generated IDs (e.g. generated-id-\\d+, :r[0-9a-z]+:, mui-\\d+, ember\\d+).

            ## Output Schema Constraint
            Emit a structured ResolvedLocatorSet object with {elementRefId, strategy, value, role}.
            Do NOT output rendered Java expression strings.
            """;

    public static final String DEFAULT_GENERATE_SYSTEM = """
            # Generate System Prompt (v7)

            You are an expert Playwright-Java engineer generating end-to-end test code and Page Object classes (§6.11, §6.12).
            - Java 21, Playwright for Java, JUnit 5, AssertJ.
            - Follow Page Object Model (POM) pattern.
            - Every locator from ResolvedLocatorSet must be used verbatim as rendered. Never invent or synthesize locators.
            - Inspect ExistingCodeContext digests and reuse or extend existing page objects.
            - Never use Thread.sleep(); rely strictly on Playwright auto-waiting assertions.
            """;

    public static final String DEFAULT_CODING_CONVENTIONS = """
            # Playwright-Java Coding Conventions (v7)
            - Place page objects in com.company.pages.
            - Place test classes in com.company.tests.
            - Action methods return this or new page object for fluent navigation.
            - Assertion statements belong exclusively in test classes, never in page objects.
            """;

    public static final String DEFAULT_RESOLVE_TASK_TEMPLATE = """
            Resolve the locators for the actions specified in the following test plan:
            %s
            """;

    private final String version;
    private final String resolveSystem;
    private final String generateSystem;
    private final String codingConventions;
    private final String resolveTaskTemplate;
    private final String bundleHash;

    public PromptBundle() {
        this(DEFAULT_VERSION, DEFAULT_RESOLVE_SYSTEM, DEFAULT_CODING_CONVENTIONS, DEFAULT_RESOLVE_TASK_TEMPLATE, DEFAULT_GENERATE_SYSTEM);
    }

    public PromptBundle(String resolveSystem, String codingConventions, String resolveTaskTemplate, String generateSystem) {
        this(DEFAULT_VERSION, resolveSystem, codingConventions, resolveTaskTemplate, generateSystem);
    }

    public PromptBundle(String version, String resolveSystem, String codingConventions, String resolveTaskTemplate, String generateSystem) {
        this.version = version != null ? version : DEFAULT_VERSION;
        this.resolveSystem = Objects.requireNonNull(resolveSystem, "resolveSystem must not be null");
        this.codingConventions = Objects.requireNonNull(codingConventions, "codingConventions must not be null");
        this.resolveTaskTemplate = Objects.requireNonNull(resolveTaskTemplate, "resolveTaskTemplate must not be null");
        this.generateSystem = Objects.requireNonNull(generateSystem, "generateSystem must not be null");
        this.bundleHash = computeHash(this.version, this.resolveSystem, this.codingConventions, this.resolveTaskTemplate, this.generateSystem);
    }

    /**
     * Loads the default v7 prompt bundle from filesystem (prompts/v7) or classpath (/prompts/v7).
     */
    public static PromptBundle load() {
        return load(DEFAULT_VERSION);
    }

    /**
     * Loads prompt bundle for the specified version (e.g. "v7").
     */
    public static PromptBundle load(String version) {
        String ver = version != null ? version : DEFAULT_VERSION;

        // 1. Try filesystem under prompts/<version>
        Path fsPath = Paths.get("prompts", ver);
        if (Files.isDirectory(fsPath)) {
            try {
                return loadFromDirectory(fsPath, ver);
            } catch (Exception e) {
                log.warn("Failed to load prompt bundle from filesystem at {}, trying classpath", fsPath, e);
            }
        }

        // 2. Try classpath under /prompts/<version>/
        String cpPrefix = "/prompts/" + ver + "/";
        String resolveSys = readClasspathResource(cpPrefix + "resolve.system.md");
        String genSys = readClasspathResource(cpPrefix + "generate.system.md");
        String conv = readClasspathResource(cpPrefix + "conventions.md");

        if (resolveSys != null && genSys != null && conv != null) {
            log.info("Loaded prompt bundle {} from classpath {}", ver, cpPrefix);
            return new PromptBundle(ver, resolveSys, conv, DEFAULT_RESOLVE_TASK_TEMPLATE, genSys);
        }

        log.info("Prompt bundle {} not found on filesystem or classpath, using built-in defaults", ver);
        return new PromptBundle(ver, DEFAULT_RESOLVE_SYSTEM, DEFAULT_CODING_CONVENTIONS, DEFAULT_RESOLVE_TASK_TEMPLATE, DEFAULT_GENERATE_SYSTEM);
    }

    public static PromptBundle loadFromDirectory(Path dir, String version) throws IOException {
        Objects.requireNonNull(dir, "directory must not be null");
        String resolveSys = Files.readString(dir.resolve("resolve.system.md"), StandardCharsets.UTF_8);
        String genSys = Files.readString(dir.resolve("generate.system.md"), StandardCharsets.UTF_8);
        String conv = Files.readString(dir.resolve("conventions.md"), StandardCharsets.UTF_8);

        log.info("Loaded prompt bundle {} from directory {}", version, dir);
        return new PromptBundle(version, resolveSys, conv, DEFAULT_RESOLVE_TASK_TEMPLATE, genSys);
    }

    private static String readClasspathResource(String resourcePath) {
        try (InputStream is = PromptBundle.class.getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public String version() {
        return version;
    }

    public String resolveSystem() {
        return resolveSystem;
    }

    public String codingConventions() {
        return codingConventions;
    }

    public String resolveTask(String planJson) {
        return resolveTaskTemplate.formatted(planJson != null ? planJson : "");
    }

    public String generateSystem() {
        return generateSystem;
    }

    public String generateTask(ParsedTestPlan plan, ResolvedLocatorSet locators) {
        String desc = plan != null ? (plan.testDescription() != null ? plan.testDescription() : plan.targetArea()) : "";
        int count = locators != null && locators.locators() != null ? locators.locators().size() : 0;
        StringBuilder sb = new StringBuilder();
        sb.append(count).append(" locators");
        if (locators != null && locators.locators() != null && !locators.locators().isEmpty()) {
            sb.append(":\n");
            for (var l : locators.locators()) {
                String expr = l.expression() != null ? l.expression() : "";
                sb.append("  - [").append(l.elementRefId()).append("] ")
                  .append(l.strategy()).append(": ").append(expr).append("\n");
            }
        }
        return """
                Test Plan:
                %s

                Resolved Locators:
                %s
                """.formatted(desc != null ? desc : "", sb.toString().trim());
    }

    public String bundleHash() {
        return bundleHash;
    }

    private static String computeHash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                if (part != null) {
                    digest.update(part.getBytes(StandardCharsets.UTF_8));
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
