package com.company.testgen.llm;

import com.company.testgen.model.DomElementSnapshot;
import com.company.testgen.model.GeneratedFile;
import com.company.testgen.model.GeneratedFileSet;
import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.PageSnapshot;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.RecordedAction;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.util.Json;
import com.company.testgen.validation.LocatorRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Offline mock implementation of LlmClient for local development, fast unit tests,
 * and self-healing retry loop verification (§6.10, §9.8, §9.9, NFR-25).
 */
public class LocalMockLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LocalMockLlmClient.class);

    @Override
    public ResolvedLocatorSet resolveLocators(ResolveRequest req) {
        Objects.requireNonNull(req, "request must not be null");

        List<ResolvedLocator> locators = new ArrayList<>();
        Set<String> seenRefs = new HashSet<>();

        ParsedTestPlan plan = null;
        if (req.planJson() != null && !req.planJson().isBlank() && !req.planJson().equals("{}")) {
            try {
                plan = Json.parse(req.planJson(), ParsedTestPlan.class);
            } catch (Exception e) {
                log.warn("Could not parse planJson in LocalMockLlmClient: {}", e.getMessage());
            }
        }

        boolean failFirst = Boolean.getBoolean("testgen.mock.failFirstAttempt");

        if (plan != null && plan.snapshots() != null) {
            for (PageSnapshot snap : plan.snapshots()) {
                if (snap.interactedElements() != null) {
                    for (DomElementSnapshot elem : snap.interactedElements()) {
                        String refId = elem.elementRefId();
                        if (refId == null || refId.isBlank() || seenRefs.contains(refId)) {
                            continue;
                        }
                        seenRefs.add(refId);

                        LocatorStrategy strategy;
                        String value;
                        String role = null;

                        String testId = elem.attributes() != null ? elem.attributes().get("data-testid") : null;
                        if (testId != null && !testId.isBlank()) {
                            strategy = LocatorStrategy.TEST_ID;
                            value = testId;
                        } else {
                            strategy = LocatorStrategy.ROLE_NAME;
                            role = elem.role() != null && !elem.role().isBlank()
                                    ? elem.role().toUpperCase(Locale.ROOT)
                                    : "BUTTON";
                            value = elem.accessibleName() != null && !elem.accessibleName().isBlank()
                                    ? elem.accessibleName()
                                    : (elem.textContent() != null && !elem.textContent().isBlank()
                                            ? elem.textContent().trim()
                                            : refId);
                        }

                        // Support -Dtestgen.mock.failFirstAttempt=true:
                        // Return non-unique locator on attempt == 0, and valid locator on attempt >= 1
                        if (failFirst && req.attempt() == 0 && locators.isEmpty()) {
                            strategy = LocatorStrategy.TEST_ID;
                            value = "mock-non-unique-locator-attempt-0";
                            role = null;
                        }

                        ResolvedLocator loc = new ResolvedLocator(refId, strategy, value, role);
                        String expr = LocatorRenderer.render(loc);
                        locators.add(new ResolvedLocator(refId, strategy, value, role, expr));
                    }
                }
            }
        }

        // Check safeSnapshotJson directly if nothing extracted yet
        if (locators.isEmpty() && req.safeSnapshotJson() != null && !req.safeSnapshotJson().isBlank() && !req.safeSnapshotJson().equals("{}")) {
            try {
                JsonNode root = Json.mapper().readTree(req.safeSnapshotJson());
                JsonNode interactions = root.has("interactions") ? root.get("interactions") : (root.isArray() ? root : null);
                if (interactions != null && interactions.isArray()) {
                    for (JsonNode elem : interactions) {
                        String refId = elem.path("elementRefId").asText(null);
                        if (refId == null || refId.isBlank() || seenRefs.contains(refId)) {
                            continue;
                        }
                        seenRefs.add(refId);

                        LocatorStrategy strategy;
                        String value;
                        String role = null;

                        String testId = elem.path("attributes").path("data-testid").asText(null);
                        if (testId != null && !testId.isBlank()) {
                            strategy = LocatorStrategy.TEST_ID;
                            value = testId;
                        } else {
                            strategy = LocatorStrategy.ROLE_NAME;
                            role = elem.has("role") ? elem.get("role").asText("BUTTON").toUpperCase(Locale.ROOT) : "BUTTON";
                            value = elem.has("accessibleName") ? elem.get("accessibleName").asText(refId) : refId;
                        }

                        if (failFirst && req.attempt() == 0 && locators.isEmpty()) {
                            strategy = LocatorStrategy.TEST_ID;
                            value = "mock-non-unique-locator-attempt-0";
                            role = null;
                        }

                        ResolvedLocator loc = new ResolvedLocator(refId, strategy, value, role);
                        String expr = LocatorRenderer.render(loc);
                        locators.add(new ResolvedLocator(refId, strategy, value, role, expr));
                    }
                }
            } catch (Exception e) {
                log.debug("Could not parse safeSnapshotJson in LocalMockLlmClient: {}", e.getMessage());
            }
        }

        // If plan had actions but no snapshot elements were resolved
        if (locators.isEmpty() && plan != null && plan.actions() != null) {
            for (RecordedAction act : plan.actions()) {
                if (act.elementRefId() != null && !act.elementRefId().isBlank() && !seenRefs.contains(act.elementRefId())) {
                    seenRefs.add(act.elementRefId());
                    String value = failFirst && req.attempt() == 0 && locators.isEmpty()
                            ? "mock-non-unique-locator-attempt-0"
                            : act.elementRefId();
                    ResolvedLocator loc = new ResolvedLocator(act.elementRefId(), LocatorStrategy.TEST_ID, value, null);
                    locators.add(new ResolvedLocator(act.elementRefId(), LocatorStrategy.TEST_ID, value, null, LocatorRenderer.render(loc)));
                }
            }
        }

        // Default fallback if nothing was extracted
        if (locators.isEmpty()) {
            String value = failFirst && req.attempt() == 0
                    ? "mock-non-unique-locator-attempt-0"
                    : "sample-submit-btn";
            ResolvedLocator loc = new ResolvedLocator("e1", LocatorStrategy.TEST_ID, value, null);
            locators.add(new ResolvedLocator("e1", LocatorStrategy.TEST_ID, value, null, LocatorRenderer.render(loc)));
        }

        return ResolvedLocatorSet.of(locators);
    }

    @Override
    public GeneratedFileSet generateCode(GenerateRequest req) {
        Objects.requireNonNull(req, "request must not be null");

        String targetArea = (req.plan() != null && req.plan().targetArea() != null && !req.plan().targetArea().isBlank())
                ? req.plan().targetArea()
                : "dashboard";
        String cleanArea = targetArea.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (cleanArea.isEmpty()) {
            cleanArea = "dashboard";
        }
        String capArea = Character.toUpperCase(cleanArea.charAt(0)) + cleanArea.substring(1);

        String filePath = "src/test/java/com/company/tests/" + cleanArea + "/" + capArea + "Test.java";

        StringBuilder sb = new StringBuilder();
        sb.append("package com.company.tests.").append(cleanArea).append(";\n\n");
        sb.append("import com.microsoft.playwright.Page;\n");
        sb.append("import com.microsoft.playwright.options.AriaRole;\n");
        sb.append("import org.testng.annotations.Test;\n\n");
        sb.append("public class ").append(capArea).append("Test {\n\n");
        sb.append("    @Test\n");
        sb.append("    public void test").append(capArea).append("Flow() {\n");
        sb.append("        Page page = null;\n");

        if (req.locators() != null && req.locators().locators() != null) {
            for (ResolvedLocator loc : req.locators().locators()) {
                String expr = (loc.expression() != null && !loc.expression().isBlank())
                        ? loc.expression()
                        : LocatorRenderer.render(loc);
                sb.append("        ").append(expr).append(";\n");
            }
        }

        sb.append("    }\n");
        sb.append("}\n");

        GeneratedFile file = new GeneratedFile(filePath, sb.toString(), true);
        return new GeneratedFileSet(List.of(file));
    }
}
