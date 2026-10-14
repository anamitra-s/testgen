package com.company.testgen.llm;

import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.ResolvedLocatorSet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Encapsulates prompt templates for Resolve and Generate phases (§6.10).
 * Computes a deterministic SHA-256 bundle hash for audit correlation (FR-33).
 */
public class PromptBundle {

    public static final String DEFAULT_RESOLVE_SYSTEM = """
            You are an expert Playwright-Java test automation engineer.
            Your task is to analyze recorded DOM element snapshots, existing page object context, and
            determine the optimal Playwright locator strategy for each recorded element.
            
            Follow the locator hierarchy strictly:
            1. TEST_ID: page.getByTestId("...")
            2. ROLE_NAME: page.getByRole(AriaRole.<ROLE>, new Page.GetByRoleOptions().setName("...").setExact(true))
            3. LABEL: page.getByLabel("...", new Page.GetByLabelOptions().setExact(true))
            4. PLACEHOLDER: page.getByPlaceholder("...", new Page.GetByPlaceholderOptions().setExact(true))
            5. TEXT: page.getByText("...", new Page.GetByTextOptions().setExact(true)) (last resort)
            
            Never emit XPath, CSS selectors, or non-semantic locators unless explicitly required.
            Ensure every element in the plan has a corresponding resolved locator.
            """;

    public static final String DEFAULT_CODING_CONVENTIONS = """
            Coding Conventions:
            - Java 21, Playwright for Java.
            - Follow the Page Object Model (POM) pattern.
            - Avoid Thread.sleep(); use Playwright assertions with automatic retries.
            - Keep test methods clean, expressive, and focused on business assertions.
            - Reuse existing page objects and helper methods whenever available.
            """;

    public static final String DEFAULT_RESOLVE_TASK_TEMPLATE = """
            Resolve the locators for the actions specified in the following test plan:
            %s
            """;

    public static final String DEFAULT_GENERATE_SYSTEM = """
            You are an expert Playwright-Java engineer.
            Generate clean, maintainable Playwright test code and Page Object classes based on the provided
            test plan, resolved locators, and existing page object definitions.
            
            Strict requirement:
            Every locator expression from the resolved locators set must be used verbatim in the generated code.
            Do not invent or synthesize new locators that are not in the resolved locators set.
            """;

    private final String resolveSystem;
    private final String codingConventions;
    private final String resolveTaskTemplate;
    private final String generateSystem;
    private final String bundleHash;

    public PromptBundle() {
        this(DEFAULT_RESOLVE_SYSTEM, DEFAULT_CODING_CONVENTIONS, DEFAULT_RESOLVE_TASK_TEMPLATE, DEFAULT_GENERATE_SYSTEM);
    }

    public PromptBundle(String resolveSystem, String codingConventions, String resolveTaskTemplate, String generateSystem) {
        this.resolveSystem = Objects.requireNonNull(resolveSystem, "resolveSystem must not be null");
        this.codingConventions = Objects.requireNonNull(codingConventions, "codingConventions must not be null");
        this.resolveTaskTemplate = Objects.requireNonNull(resolveTaskTemplate, "resolveTaskTemplate must not be null");
        this.generateSystem = Objects.requireNonNull(generateSystem, "generateSystem must not be null");
        this.bundleHash = computeHash(resolveSystem, codingConventions, resolveTaskTemplate, generateSystem);
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
        return """
                Test Plan:
                %s
                
                Resolved Locators:
                %s
                """.formatted(desc, locators != null ? locators.locators().size() + " locators" : "0 locators");
    }

    public String bundleHash() {
        return bundleHash;
    }

    private static String computeHash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
