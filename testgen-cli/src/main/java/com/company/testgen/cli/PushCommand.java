package com.company.testgen.cli;

import com.company.testgen.cli.validation.ContractValidationException;
import com.company.testgen.cli.validation.ContractValidator;
import com.company.testgen.cli.validation.ValidationReport;
import com.company.testgen.idempotency.IdempotencyKeyDeriver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * The {@code push} subcommand (§4.1, §6.3, §7.2, FR-01, FR-02, FR-03).
 * Validates recording contract v2, computes idempotency key, and displays results.
 */
@Command(
        name = "push",
        description = "Validate recording contract v2 and derive content-based idempotency key",
        mixinStandardHelpOptions = true
)
public class PushCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Parameters(
            index = "0",
            description = "Directory containing recorded-elements.json and recorded-test.js"
    )
    private Path recordingFolder;

    @Option(
            names = {"--area", "--target-area"},
            required = true,
            description = "Target application area (e.g. dashboard, composer, statsimport)"
    )
    private String targetArea;

    @Option(
            names = {"--desc", "--description"},
            required = true,
            description = "Test description"
    )
    private String testDescription;

    @Option(
            names = {"--max-age-hours"},
            defaultValue = "24",
            description = "Max recording age in hours before freshness rejection (default: 24)"
    )
    private long maxAgeHours;

    @Override
    public Integer call() {
        try {
            ContractValidator validator = new ContractValidator(Duration.ofHours(maxAgeHours));
            ValidationReport report = validator.validate(recordingFolder);

            spec.commandLine().getOut().printf("✓ Contract v%d valid (%d actions, %d interacted elements, page HTML captured)%n",
                    report.contractVersion(), report.actionCount(), report.interactionCount());

            IdempotencyKeyDeriver deriver = new IdempotencyKeyDeriver();
            String idempotencyKey = deriver.derive(recordingFolder, targetArea, testDescription);

            spec.commandLine().getOut().println("✓ Idempotency key: " + idempotencyKey);
            return 0;

        } catch (ContractValidationException e) {
            spec.commandLine().getErr().println("✗ Ingestion validation failed: " + e.getMessage());
            return 30; // Exit code 30: INVALID_RECORDING (§9.2)
        } catch (Exception e) {
            spec.commandLine().getErr().println("✗ Error processing recording: " + e.getMessage());
            return 1;
        }
    }
}
