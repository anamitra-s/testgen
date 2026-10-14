package com.company.testgen.cli.validation;

import com.company.testgen.model.RecordedAction;
import com.company.testgen.model.contract.InteractionPayload;
import com.company.testgen.model.contract.RecordedElements;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates recording folder according to Ingestion Contract v2 (§6.3, FR-01, FR-02).
 */
public final class ContractValidator {

    private static final int SUPPORTED_VERSION = 2;
    private static final Duration DEFAULT_MAX_AGE = Duration.ofHours(24);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final Duration maxAge;

    public ContractValidator() {
        this(DEFAULT_MAX_AGE);
    }

    public ContractValidator(Duration maxAge) {
        this.maxAge = maxAge != null ? maxAge : DEFAULT_MAX_AGE;
    }

    /**
     * Validates the given recording folder.
     *
     * @param folder directory containing {@code recorded-elements.json} and optional {@code recorded-test.js}
     * @return validation report on success
     * @throws ContractValidationException if any contract rule is violated
     */
    public ValidationReport validate(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) {
            throw new ContractValidationException("Recording folder does not exist or is not a directory: " + folder);
        }

        Path elementsPath = folder.resolve("recorded-elements.json");
        if (!Files.exists(elementsPath)) {
            throw new ContractValidationException("Missing required recorded-elements.json in " + folder);
        }

        RecordedElements re;
        try {
            re = MAPPER.readValue(elementsPath.toFile(), RecordedElements.class);
        } catch (IOException e) {
            throw new ContractValidationException("Failed to parse recorded-elements.json: " + e.getMessage());
        }

        require(re.contractVersion() == SUPPORTED_VERSION,
                "Recorder contract v%d required, found v%s. Upgrade the recorder."
                        .formatted(SUPPORTED_VERSION, re.contractVersion()));

        require(!re.interactions().isEmpty(), "No interacted elements captured.");

        require(re.interactions().stream().allMatch(i -> i.fingerprint() != null),
                "Contract v2 requires a fingerprint on every interaction.");

        require(re.snapshots().stream().anyMatch(s -> s.prunedHtml() != null && !s.prunedHtml().isBlank()),
                "No page HTML captured — L1 would run degraded. Re-record with recorder >= 3.1.");

        require(re.capturedAt() != null, "Missing capturedAt timestamp in recording.");

        Duration age = Duration.between(re.capturedAt(), Instant.now());
        require(age.compareTo(maxAge) <= 0,
                "Recording is %s old (limit %s). The app may have changed; please re-record."
                        .formatted(humanize(age), humanize(maxAge)));

        // Cross-file validation against recorded-test.js if present
        Path scriptPath = folder.resolve("recorded-test.js");
        List<RecordedAction> actions = parseActions(scriptPath);

        Set<String> refs = re.interactions().stream()
                .map(InteractionPayload::elementRefId)
                .collect(Collectors.toSet());

        actions.forEach(a ->
                require(a.elementRefId() == null || refs.contains(a.elementRefId()),
                        "Action %d references unknown element %s".formatted(a.index(), a.elementRefId())));

        return new ValidationReport(
                re.contractVersion(),
                actions.size(),
                re.interactions().size(),
                true,
                re.targetArea(),
                re.baseUrl()
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ContractValidationException(message);
        }
    }

    public static String humanize(Duration duration) {
        long hours = Math.max(0, duration.toHours());
        long minutes = Math.max(0, duration.toMinutesPart());
        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        return Math.max(1, duration.toMinutes()) + "m";
    }

    public static List<RecordedAction> parseActions(Path scriptPath) {
        if (scriptPath == null || !Files.exists(scriptPath)) {
            return List.of();
        }

        List<RecordedAction> actions = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(scriptPath, StandardCharsets.UTF_8);
            Pattern refPattern = Pattern.compile("(?i)(?:elementRefId[\"':\\s=]+|//\\s*(?:ref|elementRefId)?:?\\s*|/\\*\\s*(?:ref|elementRefId)?:?\\s*)([a-zA-Z0-9_-]+)");

            int index = 0;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                Matcher matcher = refPattern.matcher(trimmed);
                String refId = matcher.find() ? matcher.group(1) : null;

                if (refId != null || trimmed.startsWith("await ") || trimmed.startsWith("page.") || trimmed.contains(".click(") || trimmed.contains(".fill(")) {
                    actions.add(new RecordedAction(index++, "action", refId, null, null));
                }
            }
        } catch (IOException e) {
            // Degraded parsing - return whatever was parsed
        }
        return actions;
    }
}
