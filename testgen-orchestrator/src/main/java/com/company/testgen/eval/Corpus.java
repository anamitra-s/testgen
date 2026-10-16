package com.company.testgen.eval;

import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.contract.RecordedElements;
import com.company.testgen.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads and manages the golden evaluation corpus (§8.2, §8.7).
 */
public class Corpus {

    private static final Logger log = LoggerFactory.getLogger(Corpus.class);
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    public static final String SUITES_FILE = "suites.yaml";
    public static final String CASES_DIR = "cases";
    public static final String META_FILE = "meta.yaml";
    public static final String RECORDED_TEST_FILE = "recorded-test.js";
    public static final String RECORDED_ELEMENTS_FILE = "recorded-elements.json";
    public static final String EXPECTED_LOCATORS_FILE = "expected-locators.json";
    public static final String EXISTING_CODE_CONTEXT_FILE = "existing-code-context.json";

    private final Path rootDir;
    private final Map<String, SuiteSpec> suites;
    private final List<EvalCase> cases;

    public Corpus(Path rootDir, Map<String, SuiteSpec> suites, List<EvalCase> cases) {
        this.rootDir = rootDir;
        this.suites = new LinkedHashMap<>(suites);
        this.cases = new ArrayList<>(cases);
    }

    /**
     * Loads the evaluation corpus from {@code evals/} directory.
     */
    public static Corpus load(Path evalsDir) {
        if (evalsDir == null) {
            evalsDir = Path.of("evals");
        }
        if (!Files.exists(evalsDir)) {
            Path parentCandidate = Path.of("..").resolve(evalsDir);
            if (Files.exists(parentCandidate)) {
                evalsDir = parentCandidate;
            }
        }
        Path suitesPath = evalsDir.resolve(SUITES_FILE);
        Map<String, SuiteSpec> suites = new LinkedHashMap<>();

        if (Files.exists(suitesPath)) {
            try {
                JsonNode root = YAML_MAPPER.readTree(suitesPath.toFile());
                JsonNode suitesNode = root.has("suites") ? root.get("suites") : root;
                suitesNode.fields().forEachRemaining(entry -> {
                    String name = entry.getKey();
                    JsonNode specNode = entry.getValue();
                    String cases = specNode.has("cases") ? specNode.get("cases").asText() : null;
                    String selection = specNode.has("selection") ? specNode.get("selection").asText() : null;
                    int repeats = specNode.has("repeats") ? specNode.get("repeats").asInt(1) : 1;
                    String filter = specNode.has("filter") ? specNode.get("filter").asText() : null;
                    suites.put(name, new SuiteSpec(name, cases, selection, repeats, filter));
                });
            } catch (Exception e) {
                log.warn("Failed to parse {}: {} — using defaults", suitesPath, e.getMessage());
            }
        }

        if (!suites.containsKey("smoke")) suites.put("smoke", SuiteSpec.smoke());
        if (!suites.containsKey("golden")) suites.put("golden", SuiteSpec.golden());
        if (!suites.containsKey("hard")) suites.put("hard", SuiteSpec.hard());

        Path casesDir = evalsDir.resolve(CASES_DIR);
        List<EvalCase> loadedCases = new ArrayList<>();
        if (Files.isDirectory(casesDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(casesDir)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)) {
                        try {
                            EvalCase c = loadCase(entry);
                            loadedCases.add(c);
                        } catch (Exception e) {
                            log.error("Failed to load eval case from {}: {}", entry, e.getMessage(), e);
                        }
                    }
                }
            } catch (IOException e) {
                log.error("Failed to read cases directory at {}: {}", casesDir, e.getMessage());
            }
        }

        log.info("Corpus loaded: {} suites, {} cases from {}", suites.size(), loadedCases.size(), evalsDir);
        return new Corpus(evalsDir, suites, loadedCases);
    }

    /**
     * Loads a single evaluation case from its directory.
     */
    public static EvalCase loadCase(Path caseDir) throws IOException {
        Path metaPath = caseDir.resolve(META_FILE);
        Path testPath = caseDir.resolve(RECORDED_TEST_FILE);
        Path elementsPath = caseDir.resolve(RECORDED_ELEMENTS_FILE);
        Path locatorsPath = caseDir.resolve(EXPECTED_LOCATORS_FILE);
        Path contextPath = caseDir.resolve(EXISTING_CODE_CONTEXT_FILE);

        // 1. Meta YAML
        String caseId = caseDir.getFileName().toString();
        String targetArea = "default";
        String description = "";
        String sourcedFrom = "";
        Instant capturedAt = Instant.now();
        String difficulty = "medium";
        List<String> tags = List.of();
        String knownLimitations = "";
        String status = EvalCase.STATUS_ACTIVE;
        boolean expectedToEscalate = false;

        if (Files.exists(metaPath)) {
            JsonNode meta = YAML_MAPPER.readTree(metaPath.toFile());
            if (meta.has("caseId")) caseId = meta.get("caseId").asText();
            if (meta.has("targetArea")) targetArea = meta.get("targetArea").asText();
            if (meta.has("description")) description = meta.get("description").asText();
            if (meta.has("sourcedFrom")) sourcedFrom = meta.get("sourcedFrom").asText();
            if (meta.has("capturedAt")) {
                try {
                    capturedAt = Instant.parse(meta.get("capturedAt").asText());
                } catch (Exception ignored) {}
            }
            if (meta.has("difficulty")) difficulty = meta.get("difficulty").asText();
            if (meta.has("tags") && meta.get("tags").isArray()) {
                List<String> tagList = new ArrayList<>();
                meta.get("tags").forEach(t -> tagList.add(t.asText()));
                tags = tagList;
            }
            if (meta.has("knownLimitations")) knownLimitations = meta.get("knownLimitations").asText();
            if (meta.has("status")) status = meta.get("status").asText().trim();
            if (meta.has("expectedToEscalate")) expectedToEscalate = meta.get("expectedToEscalate").asBoolean(false);
        }

        // 2. Recorded test JS
        String recordedTestJs = Files.exists(testPath)
                ? Files.readString(testPath, StandardCharsets.UTF_8)
                : "";

        // 3. Recorded elements JSON
        RecordedElements elements = null;
        if (Files.exists(elementsPath)) {
            elements = Json.parse(Files.readString(elementsPath, StandardCharsets.UTF_8), RecordedElements.class);
        }

        // 4. Expected locators JSON (supports Map<String, ResolvedLocator>, ResolvedLocatorSet {"locators":[...]}, or List<ResolvedLocator>)
        Map<String, ResolvedLocator> expectedLocators = new LinkedHashMap<>();
        if (Files.exists(locatorsPath)) {
            String locJson = Files.readString(locatorsPath, StandardCharsets.UTF_8);
            JsonNode node = Json.mapper().readTree(locJson);
            if (node.isArray()) {
                List<ResolvedLocator> list = Json.mapper().readValue(locJson, new TypeReference<List<ResolvedLocator>>() {});
                for (ResolvedLocator rl : list) {
                    if (rl.elementRefId() != null) {
                        expectedLocators.put(rl.elementRefId(), rl);
                    }
                }
            } else if (node.isObject()) {
                if (node.has("locators") && node.get("locators").isArray()) {
                    ResolvedLocatorSet set = Json.parse(locJson, ResolvedLocatorSet.class);
                    for (ResolvedLocator rl : set.locators()) {
                        if (rl.elementRefId() != null) {
                            expectedLocators.put(rl.elementRefId(), rl);
                        }
                    }
                } else {
                    Map<String, ResolvedLocator> map = Json.mapper().readValue(locJson, new TypeReference<Map<String, ResolvedLocator>>() {});
                    expectedLocators.putAll(map);
                }
            }
        }

        // 5. Existing code context JSON
        ExistingCodeContext context = ExistingCodeContext.empty();
        if (Files.exists(contextPath)) {
            context = Json.parse(Files.readString(contextPath, StandardCharsets.UTF_8), ExistingCodeContext.class);
        }

        return new EvalCase(
                caseId, targetArea, description, sourcedFrom, capturedAt,
                difficulty, tags, knownLimitations, status, expectedToEscalate,
                recordedTestJs, elements, expectedLocators, context, caseDir
        );
    }

    /**
     * Updates the status of a case to quarantined in its {@code meta.yaml} (§8.7).
     */
    public synchronized void quarantineCase(String caseId) {
        for (int i = 0; i < cases.size(); i++) {
            EvalCase c = cases.get(i);
            if (c.caseId().equals(caseId)) {
                cases.set(i, c.withStatus(EvalCase.STATUS_QUARANTINED));
                updateMetaStatus(c.caseDir(), EvalCase.STATUS_QUARANTINED);
                log.info("Auto-quarantined eval case {} (3 consecutive nightly failures)", caseId);
                return;
            }
        }
    }

    private void updateMetaStatus(Path caseDir, String newStatus) {
        if (caseDir == null) return;
        Path metaPath = caseDir.resolve(META_FILE);
        if (!Files.exists(metaPath)) return;
        try {
            String content = Files.readString(metaPath, StandardCharsets.UTF_8);
            if (content.contains("status:")) {
                content = content.replaceAll("status:\\s*\\w+", "status: " + newStatus);
            } else {
                content = content + "\nstatus: " + newStatus + "\n";
            }
            Files.writeString(metaPath, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to write updated status to {}: {}", metaPath, e.getMessage());
        }
    }

    public List<EvalCase> load(SuiteSpec suite) {
        if (suite == null) {
            return activeCases();
        }
        List<EvalCase> active = activeCases();
        return suite.filterCases(active);
    }

    public List<EvalCase> cases() {
        return Collections.unmodifiableList(cases);
    }

    public List<EvalCase> activeCases() {
        return cases.stream().filter(EvalCase::isActive).toList();
    }

    public Map<String, SuiteSpec> suites() {
        return Collections.unmodifiableMap(suites);
    }

    public SuiteSpec suite(String name) {
        SuiteSpec spec = suites.get(name);
        if (spec != null) return spec;
        return switch (name.toLowerCase()) {
            case "smoke" -> SuiteSpec.smoke();
            case "golden" -> SuiteSpec.golden();
            case "hard" -> SuiteSpec.hard();
            default -> new SuiteSpec(name, "all", "all", 1, null);
        };
    }

    public Optional<EvalCase> findCase(String caseId) {
        return cases.stream().filter(c -> c.caseId().equals(caseId)).findFirst();
    }

    public Path rootDir() {
        return rootDir;
    }
}
