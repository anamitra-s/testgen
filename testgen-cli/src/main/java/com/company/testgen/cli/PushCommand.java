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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * The {@code push} subcommand (§3.2, §4.1, §6.3, §7.2, FR-01, FR-02, FR-03).
 * Validates recording contract v2, computes idempotency key, pushes scratch branch via Git over HTTPS,
 * and triggers Jenkins parameterized build via REST.
 */
@Command(
        name = "push",
        description = "Validate recording contract v2, push scratch branch, and trigger Jenkins pipeline",
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
    private long maxAgeHours = 24;

    @Option(
            names = {"--git-url", "--remote-url"},
            description = "Remote Git URL for pushing scratch branch"
    )
    private String gitUrl;

    @Option(
            names = {"--jenkins-url"},
            defaultValue = "${env:JENKINS_URL:-http://localhost:8080}",
            description = "Jenkins base URL (default: env JENKINS_URL or http://localhost:8080)"
    )
    private String jenkinsUrl = "http://localhost:8080";

    @Option(
            names = {"--jenkins-job"},
            defaultValue = "${env:JENKINS_JOB:-testgen-pipeline}",
            description = "Jenkins job name (default: env JENKINS_JOB or testgen-pipeline)"
    )
    private String jenkinsJob = "testgen-pipeline";

    @Option(
            names = {"--jenkins-user"},
            defaultValue = "${env:JENKINS_USER:-}",
            description = "Jenkins user for authentication (default: env JENKINS_USER)"
    )
    private String jenkinsUser;

    @Option(
            names = {"--jenkins-token"},
            defaultValue = "${env:JENKINS_TOKEN:-}",
            description = "Jenkins API token for authentication (default: env JENKINS_TOKEN)"
    )
    private String jenkinsToken;

    @Option(
            names = {"--dry-run"},
            description = "Dry-run mode: validates contract and derives key without pushing or triggering CI"
    )
    private boolean dryRun = false;

    private GitPusher gitPusher;
    private JenkinsTrigger jenkinsTrigger;

    public PushCommand() {
        this(new DefaultGitPusher(), new DefaultJenkinsTrigger());
    }

    public PushCommand(GitPusher gitPusher, JenkinsTrigger jenkinsTrigger) {
        this.gitPusher = gitPusher;
        this.jenkinsTrigger = jenkinsTrigger;
    }

    public void setGitPusher(GitPusher gitPusher) {
        this.gitPusher = gitPusher;
    }

    public void setJenkinsTrigger(JenkinsTrigger jenkinsTrigger) {
        this.jenkinsTrigger = jenkinsTrigger;
    }

    @Override
    public Integer call() {
        try {
            // 1. Ingestion contract & freshness validation (§4.1, FR-02, FR-03)
            ContractValidator validator = new ContractValidator(Duration.ofHours(maxAgeHours));
            ValidationReport report = validator.validate(recordingFolder);

            spec.commandLine().getOut().printf("✓ Contract v%d valid (%d actions, %d interacted elements, page HTML captured)%n",
                    report.contractVersion(), report.actionCount(), report.interactionCount());

            // 2. Derive content-based idempotency key (§7.2, FR-01)
            IdempotencyKeyDeriver deriver = new IdempotencyKeyDeriver();
            String idempotencyKey = deriver.derive(recordingFolder, targetArea, testDescription);

            spec.commandLine().getOut().println("✓ Idempotency key: " + idempotencyKey);

            // 3. Push scratch branch & trigger Jenkins build (§3.2, §4.1)
            String scratchBranch = "testgen-scratch/" + UUID.randomUUID();

            if (!dryRun) {
                if (gitUrl != null && !gitUrl.isBlank()) {
                    gitPusher.push(recordingFolder, scratchBranch, gitUrl);
                    spec.commandLine().getOut().println("✓ Pushed " + scratchBranch);
                } else {
                    spec.commandLine().getOut().println("✓ Pushed " + scratchBranch + " (local simulation)");
                }

                Map<String, String> jenkinsParams = Map.of(
                        "IDEMPOTENCY_KEY", idempotencyKey,
                        "TARGET_AREA", targetArea,
                        "TEST_DESC", testDescription,
                        "SCRATCH_BRANCH", scratchBranch
                );

                String buildUrl = jenkinsTrigger.trigger(jenkinsUrl, jenkinsJob, jenkinsUser, jenkinsToken, jenkinsParams);
                spec.commandLine().getOut().println("✓ Triggered  " + buildUrl);

            } else {
                spec.commandLine().getOut().println("✓ Pushed " + scratchBranch + " [dry-run]");
                spec.commandLine().getOut().println("✓ Triggered  " + jenkinsUrl + "/job/" + jenkinsJob + "/1 [dry-run]");
            }

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
