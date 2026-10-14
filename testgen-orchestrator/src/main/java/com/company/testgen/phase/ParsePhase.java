package com.company.testgen.phase;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Phase 1: Ingestion & parsing of recording artifacts (§6.2, §6.3, §6.13).
 * Extracts recorded actions, page snapshots, detects login flows, and flags data mutations.
 */
public class ParsePhase {

    private static final Logger log = LoggerFactory.getLogger(ParsePhase.class);
    private static final String RECORDED_ELEMENTS_FILE = "recorded-elements.json";

    public ParsedTestPlan execute(TestGenerationJob job) {
        Objects.requireNonNull(job, "job cannot be null");
        log.info("Executing ParsePhase for job {} (targetArea={})", job.id(), job.targetArea());

        Path recordingDir = job.recordingDir();
        if (recordingDir == null) {
            log.warn("No recordingDir specified for job {}; returning empty test plan", job.id());
            return new ParsedTestPlan(
                    job.targetArea(),
                    job.testDescription() != null ? job.testDescription() : "Test " + job.targetArea(),
                    List.of(),
                    List.of(),
                    false,
                    false
            );
        }

        Path recordedElementsPath = Files.isDirectory(recordingDir)
                ? recordingDir.resolve(RECORDED_ELEMENTS_FILE)
                : recordingDir;

        if (!Files.exists(recordedElementsPath)) {
            log.warn("Recorded elements file not found at {}; returning empty test plan", recordedElementsPath);
            return new ParsedTestPlan(
                    job.targetArea(),
                    job.testDescription() != null ? job.testDescription() : "Test " + job.targetArea(),
                    List.of(),
                    List.of(),
                    false,
                    false
            );
        }

        try {
            String json = Files.readString(recordedElementsPath);
            RecordedElements recorded = Json.parse(json, RecordedElements.class);

            List<RecordedAction> actions = new ArrayList<>();
            List<PageSnapshot> snapshots = new ArrayList<>();
            boolean containsLoginFlow = false;
            boolean mutatesData = false;

            if (recorded != null) {
                Map<String, List<InteractionPayload>> interactionsByState = recorded.interactions() != null
                        ? recorded.interactions().stream()
                                .filter(el -> el.stateId() != null)
                                .collect(Collectors.groupingBy(InteractionPayload::stateId))
                        : Map.of();

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

                        snapshots.add(new PageSnapshot(
                                snap.stateId(),
                                snap.url() != null ? snap.url() : "",
                                snap.prunedHtml() != null ? snap.prunedHtml() : "",
                                elementSnapshots,
                                recorded.capturedAt() != null ? recorded.capturedAt() : Instant.now()
                        ));

                        if (snap.url() != null) {
                            String lowerUrl = snap.url().toLowerCase(Locale.ROOT);
                            if (lowerUrl.contains("login") || lowerUrl.contains("signin") || lowerUrl.contains("auth")) {
                                containsLoginFlow = true;
                            }
                        }
                    }
                }

                if (recorded.interactions() != null) {
                    int idx = 0;
                    for (InteractionPayload el : recorded.interactions()) {
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

                        if (el.role() != null && el.role().equalsIgnoreCase("password")) {
                            containsLoginFlow = true;
                        }
                        if (el.attributes() != null) {
                            String inputType = el.attributes().get("type");
                            if ("password".equalsIgnoreCase(inputType)) {
                                containsLoginFlow = true;
                            }
                        }
                        if (el.accessibleName() != null) {
                            String lowerName = el.accessibleName().toLowerCase(Locale.ROOT);
                            if (lowerName.contains("log in") || lowerName.contains("sign in")) {
                                containsLoginFlow = true;
                            }
                            if (lowerName.contains("delete") || lowerName.contains("remove") || lowerName.contains("submit")) {
                                mutatesData = true;
                            }
                        }
                    }
                }
            }

            log.info("Parsed test plan for job {}: {} actions, {} snapshots, loginFlow={}, mutatesData={}",
                    job.id(), actions.size(), snapshots.size(), containsLoginFlow, mutatesData);

            return new ParsedTestPlan(
                    job.targetArea(),
                    job.testDescription() != null ? job.testDescription() : "Test " + job.targetArea(),
                    actions,
                    snapshots,
                    containsLoginFlow,
                    mutatesData
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to read recorded elements from " + recordedElementsPath + ": " + e.getMessage(), e);
        }
    }
}
