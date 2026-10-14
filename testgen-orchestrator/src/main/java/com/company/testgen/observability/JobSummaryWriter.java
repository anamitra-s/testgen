package com.company.testgen.observability;

import com.company.testgen.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Emits the final job-summary.json as a build artifact upon termination (§9.3, FR-35).
 */
public class JobSummaryWriter {

    private static final Logger log = LoggerFactory.getLogger(JobSummaryWriter.class);
    public static final String DEFAULT_FILENAME = "job-summary.json";

    private final Path outputPath;

    public JobSummaryWriter() {
        this(Path.of(DEFAULT_FILENAME));
    }

    public JobSummaryWriter(Path target) {
        Objects.requireNonNull(target, "target cannot be null");
        if (Files.isDirectory(target) || !target.toString().endsWith(".json")) {
            this.outputPath = target.resolve(DEFAULT_FILENAME);
        } else {
            this.outputPath = target;
        }
    }

    /**
     * Serializes and writes the job summary JSON to the output path.
     *
     * @param summary the summary to write
     * @return the written file path
     * @throws IOException if writing fails
     */
    public Path write(JobSummary summary) throws IOException {
        Objects.requireNonNull(summary, "summary cannot be null");
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }

        Json.mapper().writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), summary);
        log.info("Emitted job summary artifact to {}", outputPath.toAbsolutePath());
        return outputPath;
    }

    public Path outputPath() {
        return outputPath;
    }
}
