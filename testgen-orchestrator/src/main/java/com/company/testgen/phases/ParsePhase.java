package com.company.testgen.phases;

import com.company.testgen.model.DomElementSnapshot;
import com.company.testgen.model.PageSnapshot;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.RecordedAction;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.model.contract.InteractionPayload;
import com.company.testgen.model.contract.RecordedElements;
import com.company.testgen.model.contract.SnapshotPayload;
import com.company.testgen.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Phase 1: Ingestion & parsing of recording artifacts (§3.6, §6.2, §6.3, §6.13).
 * Parses recorded-test.js and recorded-elements.json into ParsedTestPlan and List<PageSnapshot>,
 * with automated login-flow detection and data-mutation detection.
 */
public class ParsePhase {

    private static final Logger log = LoggerFactory.getLogger(ParsePhase.class);

    public static final String RECORDED_ELEMENTS_FILE = "recorded-elements.json";
    public static final String RECORDED_TEST_FILE = "recorded-test.js";

    private static final Pattern REF_PATTERN = Pattern.compile(
            "(?i)(?:elementRefId[\"':\\s=]+|//\\s*(?:ref|elementRefId)\\s*:?\\s*|/\\*\\s*(?:ref|elementRefId)\\s*:?\\s*)([a-zA-Z0-9_-]+)"
    );

    private static final Pattern VALUE_PATTERN = Pattern.compile(
            "\\.(?:fill|type|press|selectOption|goto)\\(\\s*[\"']([^\"']*)[\"']"
    );

    private static final Set<String> LOGIN_KEYWORDS = Set.of(
            "login", "sign in", "signin", "log in", "auth", "oauth", "sso", "authenticate", "password"
    );

    private static final Set<String> MUTATION_KEYWORDS = Set.of(
            "delete", "remove", "destroy", "drop", "purge", "clear all", "reset",
            "save", "create", "submit", "apply", "insert", "modify", "update",
            "confirm", "publish", "discard", "archive", "unlink"
    );

    public ParsedTestPlan execute(TestGenerationJob job) {
        Objects.requireNonNull(job, "job cannot be null");
        log.info("Executing ParsePhase for job {} (targetArea={})", job.id(), job.targetArea());

        Path recordingDir = job.recordingDir();
        return parse(recordingDir, job.targetArea(), job.testDescription());
    }

    /**
     * Parses recording folder containing recorded-elements.json and optional recorded-test.js.
     */
    public ParsedTestPlan parse(Path recordingDir, String targetArea, String testDescription) {
        String effectiveTargetArea = targetArea != null ? targetArea : "default";
        String effectiveDescription = testDescription != null ? testDescription : "Test " + effectiveTargetArea;

        if (recordingDir == null) {
            log.warn("No recordingDir specified; returning empty test plan");
            return new ParsedTestPlan(effectiveTargetArea, effectiveDescription, List.of(), List.of(), false, false);
        }

        Path elementsPath = Files.isDirectory(recordingDir)
                ? recordingDir.resolve(RECORDED_ELEMENTS_FILE)
                : recordingDir;

        Path scriptPath = Files.isDirectory(recordingDir)
                ? recordingDir.resolve(RECORDED_TEST_FILE)
                : recordingDir.getParent() != null ? recordingDir.getParent().resolve(RECORDED_TEST_FILE) : null;

        if (!Files.exists(elementsPath)) {
            log.warn("Recorded elements file not found at {}; returning empty test plan", elementsPath);
            return new ParsedTestPlan(effectiveTargetArea, effectiveDescription, List.of(), List.of(), false, false);
        }

        try {
            String elementsJson = Files.readString(elementsPath, StandardCharsets.UTF_8);
            String testJs = (scriptPath != null && Files.exists(scriptPath))
                    ? Files.readString(scriptPath, StandardCharsets.UTF_8)
                    : null;

            return parse(testJs, elementsJson, effectiveTargetArea, effectiveDescription);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read recording artifacts: " + e.getMessage(), e);
        }
    }

    /**
     * Core parser converting recorded JavaScript and JSON elements into a ParsedTestPlan.
     */
    public ParsedTestPlan parse(String testJsContent, String elementsJsonContent, String targetArea, String testDescription) {
        RecordedElements recorded = Json.parse(elementsJsonContent, RecordedElements.class);
        if (recorded == null) {
            return new ParsedTestPlan(targetArea, testDescription, List.of(), List.of(), false, false);
        }

        Map<String, List<InteractionPayload>> interactionsByState = recorded.interactions() != null
                ? recorded.interactions().stream()
                        .filter(el -> el.stateId() != null)
                        .collect(Collectors.groupingBy(InteractionPayload::stateId))
                : Map.of();

        Map<String, InteractionPayload> interactionsByRef = recorded.interactions() != null
                ? recorded.interactions().stream()
                        .filter(el -> el.elementRefId() != null)
                        .collect(Collectors.toMap(InteractionPayload::elementRefId, el -> el, (a, b) -> a))
                : Map.of();

        // 1. Build List<PageSnapshot>
        List<PageSnapshot> snapshots = new ArrayList<>();
        if (recorded.snapshots() != null) {
            for (SnapshotPayload snap : recorded.snapshots()) {
                List<InteractionPayload> stateInteractions = interactionsByState.getOrDefault(snap.stateId(), List.of());
                List<DomElementSnapshot> elementSnapshots = stateInteractions.stream()
                        .map(el -> new DomElementSnapshot(
                                el.elementRefId(),
                                el.tagName(),
                                el.attributes(),
                                el.textContent(),
                                el.role(),
                                el.accessibleName(),
                                el.fingerprint()
                        ))
                        .toList();

                boolean isTruncated = Boolean.TRUE.equals(snap.truncated());
                snapshots.add(new PageSnapshot(
                        snap.stateId(),
                        snap.url() != null ? snap.url() : "",
                        snap.prunedHtml() != null ? snap.prunedHtml() : "",
                        elementSnapshots,
                        recorded.capturedAt() != null ? recorded.capturedAt() : Instant.now(),
                        isTruncated
                ));
            }
        }

        // 2. Parse Actions from recorded-test.js or fallback to recorded-elements.json
        List<RecordedAction> actions = parseActions(testJsContent, recorded.interactions(), interactionsByRef);

        // 3. Detect Login Flow
        boolean containsLoginFlow = detectLoginFlow(snapshots, actions, recorded.interactions(), testJsContent);

        // 4. Detect Data Mutation
        boolean mutatesData = detectDataMutation(actions, recorded.interactions(), testJsContent);

        log.info("Parsed test plan: {} actions, {} snapshots, loginFlow={}, mutatesData={}",
                actions.size(), snapshots.size(), containsLoginFlow, mutatesData);

        return new ParsedTestPlan(
                targetArea,
                testDescription,
                actions,
                snapshots,
                containsLoginFlow,
                mutatesData
        );
    }

    /**
     * Parses actions from JavaScript source code, correlating with captured DOM interactions.
     */
    public List<RecordedAction> parseActions(String scriptContent,
                                            List<InteractionPayload> interactions,
                                            Map<String, InteractionPayload> interactionsByRef) {
        if (scriptContent == null || scriptContent.isBlank()) {
            return fallbackActionsFromInteractions(interactions);
        }

        List<RecordedAction> actions = new ArrayList<>();
        String[] lines = scriptContent.split("\\R");
        int actionIndex = 0;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//") && !line.contains("ref") && !line.contains("elementRefId")) {
                continue;
            }

            Matcher refMatcher = REF_PATTERN.matcher(line);
            String refId = refMatcher.find() ? refMatcher.group(1) : null;

            Matcher valMatcher = VALUE_PATTERN.matcher(line);
            String value = valMatcher.find() ? valMatcher.group(1) : null;

            String type = inferActionType(line);
            if (type == null && refId == null) {
                continue;
            }
            if (type == null) {
                type = "click";
            }

            String stateId = null;
            if (refId != null && interactionsByRef.containsKey(refId)) {
                InteractionPayload payload = interactionsByRef.get(refId);
                stateId = payload.stateId();
                if (value == null && ("fill".equals(type) || "input".equalsIgnoreCase(payload.tagName()))) {
                    value = payload.textContent();
                }
            }

            actions.add(new RecordedAction(actionIndex++, type, refId, value, stateId));
        }

        if (actions.isEmpty()) {
            return fallbackActionsFromInteractions(interactions);
        }

        return actions;
    }

    private List<RecordedAction> fallbackActionsFromInteractions(List<InteractionPayload> interactions) {
        if (interactions == null || interactions.isEmpty()) {
            return List.of();
        }

        List<RecordedAction> actions = new ArrayList<>();
        int idx = 0;
        for (InteractionPayload el : interactions) {
            String type = "click";
            String value = null;
            if ("input".equalsIgnoreCase(el.tagName()) || "textarea".equalsIgnoreCase(el.tagName())) {
                type = "fill";
                value = el.textContent();
            }
            actions.add(new RecordedAction(
                    el.actionIndex() != null ? el.actionIndex() : idx++,
                    type,
                    el.elementRefId(),
                    value,
                    el.stateId()
            ));
        }
        return actions;
    }

    private String inferActionType(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains(".click(") || lower.contains(".dblclick(")) return "click";
        if (lower.contains(".fill(") || lower.contains(".type(")) return "fill";
        if (lower.contains(".press(")) return "press";
        if (lower.contains(".check(") || lower.contains(".uncheck(")) return "check";
        if (lower.contains(".selectoption(")) return "select";
        if (lower.contains(".goto(") || lower.contains("navigate")) return "navigate";
        return null;
    }

    /**
     * Detects login and authentication flows (§3.6, §6.3).
     */
    public boolean detectLoginFlow(List<PageSnapshot> snapshots,
                                  List<RecordedAction> actions,
                                  List<InteractionPayload> interactions,
                                  String scriptContent) {
        // 1. Snapshot URLs
        if (snapshots != null) {
            for (PageSnapshot snap : snapshots) {
                if (snap.normalizedUrl() != null && containsAnyKeyword(snap.normalizedUrl(), LOGIN_KEYWORDS)) {
                    return true;
                }
            }
        }

        // 2. Interacted elements attributes, roles, names
        if (interactions != null) {
            for (InteractionPayload el : interactions) {
                if (el.role() != null && containsAnyKeyword(el.role(), LOGIN_KEYWORDS)) {
                    return true;
                }
                if (el.accessibleName() != null && containsAnyKeyword(el.accessibleName(), LOGIN_KEYWORDS)) {
                    return true;
                }
                if (el.attributes() != null) {
                    String type = el.attributes().get("type");
                    if ("password".equalsIgnoreCase(type)) {
                        return true;
                    }
                    String autocomplete = el.attributes().get("autocomplete");
                    if (autocomplete != null && autocomplete.toLowerCase(Locale.ROOT).contains("password")) {
                        return true;
                    }
                    String id = el.attributes().get("id");
                    if (id != null && containsAnyKeyword(id, LOGIN_KEYWORDS)) {
                        return true;
                    }
                    String name = el.attributes().get("name");
                    if (name != null && containsAnyKeyword(name, LOGIN_KEYWORDS)) {
                        return true;
                    }
                }
            }
        }

        // 3. Script text
        if (scriptContent != null && !scriptContent.isBlank()) {
            String lowerScript = scriptContent.toLowerCase(Locale.ROOT);
            if (lowerScript.contains("password") || lowerScript.contains("signin") || lowerScript.contains("log in")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Detects data-mutating actions (create, delete, save, update, confirm) (§3.6, §6.3).
     */
    public boolean detectDataMutation(List<RecordedAction> actions,
                                      List<InteractionPayload> interactions,
                                      String scriptContent) {
        if (interactions != null) {
            for (InteractionPayload el : interactions) {
                if (el.accessibleName() != null && containsAnyKeyword(el.accessibleName(), MUTATION_KEYWORDS)) {
                    return true;
                }
                if (el.attributes() != null) {
                    String testId = el.attributes().get("data-testid");
                    if (testId != null && containsAnyKeyword(testId, MUTATION_KEYWORDS)) {
                        return true;
                    }
                    String id = el.attributes().get("id");
                    if (id != null && containsAnyKeyword(id, MUTATION_KEYWORDS)) {
                        return true;
                    }
                    String type = el.attributes().get("type");
                    if ("submit".equalsIgnoreCase(type)) {
                        return true;
                    }
                    String clazz = el.attributes().get("class");
                    if (clazz != null && (clazz.contains("btn-danger") || clazz.contains("btn-delete"))) {
                        return true;
                    }
                }
            }
        }

        if (actions != null) {
            for (RecordedAction act : actions) {
                if (act.value() != null && containsAnyKeyword(act.value(), MUTATION_KEYWORDS)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean containsAnyKeyword(String text, Set<String> keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}
