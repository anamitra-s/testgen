package com.company.testgen;

import com.company.testgen.config.PipelineProperties;
import com.company.testgen.gitlab.GitLabIntegrationService;
import com.company.testgen.idempotency.EffectExecutor;
import com.company.testgen.idempotency.IdempotencyGate;
import com.company.testgen.idempotency.IdempotencyKeyDeriver;
import com.company.testgen.idempotency.LeaseLostException;
import com.company.testgen.idempotency.ReconcileReport;
import com.company.testgen.idempotency.ReconcilerJob;
import com.company.testgen.llm.BudgetExceededException;
import com.company.testgen.llm.InMemoryCostEventRepository;
import com.company.testgen.llm.LangChain4jLlmClient;
import com.company.testgen.llm.PriceBook;
import com.company.testgen.llm.PromptBundle;
import com.company.testgen.llm.TokenBudgetGuard;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.observability.JobSummaryWriter;
import com.company.testgen.orchestrator.CorrectiveFeedbackBuilder;
import com.company.testgen.orchestrator.PipelineOrchestrator;
import com.company.testgen.orchestrator.PipelineOutcome;
import com.company.testgen.persistence.Db;
import com.company.testgen.persistence.EffectRepository;
import com.company.testgen.eval.Corpus;
import com.company.testgen.eval.EvalGate;
import com.company.testgen.eval.EvalRun;
import com.company.testgen.eval.EvalRunner;
import com.company.testgen.eval.EvalScorer;
import com.company.testgen.eval.GateOutcome;
import com.company.testgen.eval.RunConfig;
import com.company.testgen.eval.SuiteSpec;
import com.company.testgen.persistence.EvalRunRecord;
import com.company.testgen.persistence.EvalRepository;
import com.company.testgen.persistence.InMemoryEffectRepository;
import com.company.testgen.persistence.InMemoryEvalRepository;
import com.company.testgen.persistence.InMemoryJobRepository;
import com.company.testgen.persistence.JdbiEffectRepository;
import com.company.testgen.persistence.JdbiEvalRepository;
import com.company.testgen.persistence.JdbiJobRepository;
import com.company.testgen.persistence.JdbiRetryAttemptRepository;
import com.company.testgen.persistence.JdbiValidationResultRepository;
import com.company.testgen.persistence.JdbiVerificationRepository;
import com.company.testgen.persistence.JobRepository;
import com.company.testgen.persistence.TargetAreaLock;
import com.company.testgen.phase.ContextAssemblyPhase;
import com.company.testgen.phase.ParsePhase;
import com.company.testgen.phase.RedactionPhase;
import com.company.testgen.phases.AuthSessionProvider;
import com.company.testgen.phases.DefaultCheckstyleValidator;
import com.company.testgen.phases.GeneratePhase;
import com.company.testgen.phases.LintPhase;
import com.company.testgen.phases.VerifyPhase;
import com.company.testgen.redaction.RedactionFailedException;
import com.company.testgen.validation.LocatorGrammarValidator;
import com.company.testgen.validation.StaticDomValidator;
import org.gitlab4j.api.GitLabApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Main entry point for testgen-orchestrator implementing picocli subcommands (§9.2, §9.7):
 * {@code run}, {@code eval}, {@code reconcile}, and {@code migrate}.
 * Strictly enforces the exit code contract defined in §9.2:
 * 0: Success (MR opened)
 * 10: Escalated (Draft MR opened, UNSTABLE)
 * 20: No-op (Already processed or in-flight, SUCCESS)
 * 30: Precondition failure (invalid contract/recording, FAILURE)
 * 40: Infrastructure failure (DB/GitLab/API unreachable, FAILURE)
 * 41: Lease lost (another process took over, SUCCESS no-op)
 * 50: Budget exceeded (UNSTABLE)
 * 60: Redaction failure (fail-closed, FAILURE)
 * 70: Configuration error (FAILURE)
 */
@Command(
        name = "testgen-orchestrator",
        description = "Automated test generation orchestrator",
        mixinStandardHelpOptions = true,
        subcommands = {
                Main.RunCommand.class,
                Main.EvalCommand.class,
                Main.ReconcileCommand.class,
                Main.MigrateCommand.class
        }
)
public class Main implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return PipelineOutcome.EXIT_SUCCESS;
    }

    public static int run(String... args) {
        CommandLine cmd = new CommandLine(new Main());
        cmd.setExecutionExceptionHandler(new ExitCodeExceptionHandler());
        cmd.setParameterExceptionHandler(new ConfigErrorParameterHandler());
        return cmd.execute(args);
    }

    public static void main(String... args) {
        int exitCode = run(args);
        System.exit(exitCode);
    }

    /**
     * {@code run} subcommand: executes full end-to-end test generation pipeline (§6.13, §9.2).
     */
    @Command(name = "run", description = "Run the test generation pipeline for a recording", mixinStandardHelpOptions = true)
    public static class RunCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Recording folder containing recorded-elements.json")
        private Path recordingFolder;

        @Option(names = {"--area", "--target-area"}, required = true, description = "Target area (e.g. dashboard)")
        private String targetArea;

        @Option(names = {"--desc", "--description"}, required = true, description = "Test description")
        private String testDescription;

        @Option(names = {"--idempotency-key"}, description = "Explicit idempotency key (derived if omitted)")
        private String idempotencyKey;

        @Option(names = {"--requester"}, defaultValue = "jenkins", description = "Requester identity")
        private String requester = "jenkins";

        @Option(names = {"--branch"}, defaultValue = "scratch", description = "Recording branch")
        private String branch = "scratch";

        @Option(names = {"--summary-file"}, defaultValue = "job-summary.json", description = "Path for job summary JSON")
        private Path summaryFile = Path.of("job-summary.json");

        @Option(names = {"--db-url"}, defaultValue = "${env:TESTGEN_DB_URL:-}", description = "PostgreSQL JDBC URL")
        private String dbUrl;

        @Option(names = {"--db-user"}, defaultValue = "${env:TESTGEN_DB_USER:-testgen}", description = "PostgreSQL user")
        private String dbUser = "testgen";

        @Option(names = {"--db-password"}, defaultValue = "${env:TESTGEN_DB_PASSWORD:-testgen}", description = "PostgreSQL password")
        private String dbPassword = "testgen";

        @Option(names = {"--gitlab-url"}, defaultValue = "${env:GITLAB_URL:-}", description = "GitLab API URL")
        private String gitlabUrl;

        @Option(names = {"--gitlab-token"}, defaultValue = "${env:GITLAB_TOKEN:-}", description = "GitLab API token")
        private String gitlabToken;

        @Option(names = {"--gitlab-project-id"}, defaultValue = "${env:GITLAB_PROJECT_ID:-}", description = "GitLab project ID or path")
        private String gitlabProjectId;

        @Option(names = {"--workspace"}, description = "Workspace path for L2 execution")
        private Path workspace;

        @Option(names = {"--max-attempts"}, defaultValue = "3", description = "Max resolve retry attempts")
        private int maxAttempts = 3;

        @Option(names = {"--verify-blocking"}, defaultValue = "false", description = "Whether L2 failure blocks MR")
        private boolean verifyBlocking = false;

        @Override
        public Integer call() throws Exception {
            if (recordingFolder == null || !Files.exists(recordingFolder)
                    || !Files.exists(recordingFolder.resolve("recorded-elements.json"))) {
                log.error("Precondition failed: valid recording folder does not exist: {}", recordingFolder);
                return PipelineOutcome.EXIT_PRECONDITION_FAILURE; // 30
            }

            // Derive idempotency key if omitted
            String effectiveKey = idempotencyKey;
            if (effectiveKey == null || effectiveKey.isBlank()) {
                IdempotencyKeyDeriver deriver = new IdempotencyKeyDeriver();
                effectiveKey = deriver.derive(recordingFolder, targetArea, testDescription);
            }

            UUID jobId = UUID.randomUUID();
            TestGenerationJob job = new TestGenerationJob(
                    jobId, effectiveKey, targetArea, testDescription,
                    requester, branch, "prompt-v1", "claude-sonnet-5",
                    "1.0.0", "2.0.0", "main", "worker", 0L,
                    recordingFolder
            );

            // Configure DB & Repositories
            Db db = null;
            JobRepository jobRepo;
            EffectRepository effectRepo;
            TargetAreaLock areaLock;

            if (dbUrl != null && !dbUrl.isBlank()) {
                try {
                    db = Db.create(dbUrl, dbUser, dbPassword);
                    jobRepo = new JdbiJobRepository(db.jdbi());
                    effectRepo = new JdbiEffectRepository(db.jdbi());
                    areaLock = TargetAreaLock.create(db.dataSource());
                } catch (Exception e) {
                    log.error("Failed to connect to database at {}: {}", dbUrl, e.getMessage());
                    return PipelineOutcome.EXIT_INFRA_FAILURE;
                }
            } else {
                jobRepo = new InMemoryJobRepository();
                effectRepo = new InMemoryEffectRepository();
                areaLock = TargetAreaLock.inMemory();
            }

            try {
                IdempotencyGate gate = new IdempotencyGate(db, jobRepo);
                EffectExecutor effects = new EffectExecutor(effectRepo);

                GitLabIntegrationService gitLab = null;
                if (gitlabUrl != null && !gitlabUrl.isBlank() && gitlabToken != null && !gitlabToken.isBlank()) {
                    gitLab = new GitLabIntegrationService(gitlabUrl, gitlabToken, gitlabProjectId, effects);
                }

                JobSummaryWriter summaryWriter = new JobSummaryWriter(summaryFile);
                ParsePhase parse = new ParsePhase();
                RedactionPhase redaction = new RedactionPhase();
                ContextAssemblyPhase contextPhase = new ContextAssemblyPhase();

                // LLM and generation wiring
                LangChain4jLlmClient llm = null;
                PipelineProperties props = PipelineProperties.load();
                if (props.llm().apiKey() != null && !props.llm().apiKey().isBlank() && !props.llm().apiKey().equals("dummy-key")) {
                    TokenBudgetGuard budget = new TokenBudgetGuard(
                            props.budget().maxTokensPerJob(),
                            props.budget().maxUsdPerJob(),
                            new InMemoryCostEventRepository(),
                            new PriceBook()
                    );
                    PromptBundle prompts = PromptBundle.load();
                    llm = new LangChain4jLlmClient(props, budget, prompts);
                }

                LintPhase lint = new LintPhase(new DefaultCheckstyleValidator());
                GeneratePhase generate = llm != null ? new GeneratePhase(llm, lint) : null;
                VerifyPhase verify = new VerifyPhase(new AuthSessionProvider(), Duration.ofMinutes(5));

                PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                        parse,
                        redaction,
                        contextPhase,
                        llm,
                        new LocatorGrammarValidator(),
                        new StaticDomValidator(),
                        generate,
                        verify,
                        gitLab,
                        areaLock,
                        gate,
                        jobRepo,
                        db != null ? new JdbiRetryAttemptRepository(db.jdbi()) : null,
                        db != null ? new JdbiValidationResultRepository(db.jdbi()) : null,
                        db != null ? new JdbiVerificationRepository(db.jdbi()) : null,
                        summaryWriter,
                        new CorrectiveFeedbackBuilder(),
                        maxAttempts,
                        verifyBlocking,
                        workspace
                );

                PipelineOutcome outcome = orchestrator.run(job);
                log.info("Pipeline completed with exit code {}: {}", outcome.exitCode(), outcome.state());
                return outcome.exitCode();

            } finally {
                if (db != null) {
                    db.close();
                }
            }
        }
    }

    /**
     * {@code eval} subcommand: runs golden corpus evaluation or promotes baseline (§8).
     */
    @Command(name = "eval", description = "Run prompt/model evaluation or promote baseline", mixinStandardHelpOptions = true)
    public static class EvalCommand implements Callable<Integer> {

        @Option(names = {"--suite"}, defaultValue = "smoke", description = "Suite to run (smoke, golden, hard)")
        private String suite = "smoke";

        @Option(names = {"-k", "--repeats"}, defaultValue = "1", description = "Repeats per test case")
        private int repeats = 1;

        @Option(names = {"--gate"}, defaultValue = "advisory", description = "Gate mode: blocking or advisory")
        private String gate = "advisory";

        @Option(names = {"--concurrency"}, defaultValue = "4", description = "Concurrency level")
        private int concurrency = 4;

        @Option(names = {"--report"}, defaultValue = "eval-report.json", description = "Report output path")
        private Path reportPath = Path.of("eval-report.json");

        @Option(names = {"--corpus-dir"}, defaultValue = "evals/cases", description = "Corpus directory")
        private Path corpusDir = Path.of("evals/cases");

        @Option(names = {"--promote-baseline"}, description = "Run UUID to promote as new baseline")
        private String promoteBaselineId;

        @Option(names = {"--db-url"}, defaultValue = "${env:TESTGEN_DB_URL:-}", description = "PostgreSQL JDBC URL")
        private String dbUrl;

        @Option(names = {"--db-user"}, defaultValue = "${env:TESTGEN_DB_USER:-testgen}", description = "PostgreSQL user")
        private String dbUser = "testgen";

        @Option(names = {"--db-password"}, defaultValue = "${env:TESTGEN_DB_PASSWORD:-testgen}", description = "PostgreSQL password")
        private String dbPassword = "testgen";

        @Override
        public Integer call() throws Exception {
            Db db = null;
            EvalRepository evalRepo;
            if (dbUrl != null && !dbUrl.isBlank()) {
                try {
                    db = Db.create(dbUrl, dbUser, dbPassword);
                    evalRepo = new JdbiEvalRepository(db.jdbi());
                } catch (Exception e) {
                    log.error("Failed to connect to database at {}: {}", dbUrl, e.getMessage());
                    return PipelineOutcome.EXIT_INFRA_FAILURE;
                }
            } else {
                evalRepo = new InMemoryEvalRepository();
            }

            try {
                if (promoteBaselineId != null && !promoteBaselineId.isBlank()) {
                    UUID runId = UUID.fromString(promoteBaselineId.trim());
                    evalRepo.promoteBaseline(runId);
                    log.info("Successfully promoted eval run {} as new baseline", runId);
                    return PipelineOutcome.EXIT_SUCCESS;
                }

                log.info("Running eval suite '{}' with repeats={}, gate={}", suite, repeats, gate);
                Path evalsDir = corpusDir.endsWith("cases") ? corpusDir.getParent() : corpusDir;
                Corpus corpus = Corpus.load(evalsDir != null ? evalsDir : Path.of("evals"));
                SuiteSpec spec = corpus.suite(suite);
                if (repeats > 0) {
                    spec = new SuiteSpec(spec.name(), spec.cases(), spec.selection(), repeats, spec.filter());
                }

                PipelineProperties props = PipelineProperties.load();
                LangChain4jLlmClient llm = null;
                if (props.llm().apiKey() != null && !props.llm().apiKey().isBlank() && !props.llm().apiKey().equals("dummy-key")) {
                    TokenBudgetGuard budget = new TokenBudgetGuard(
                            props.budget().maxTokensPerJob(),
                            props.budget().maxUsdPerJob(),
                            new InMemoryCostEventRepository(),
                            new PriceBook()
                    );
                    PromptBundle prompts = PromptBundle.load();
                    llm = new LangChain4jLlmClient(props, budget, prompts);
                }

                RunConfig config = new RunConfig(
                        concurrency,
                        repeats,
                        gate,
                        "golden".equalsIgnoreCase(suite) ? "NIGHTLY" : "MANUAL",
                        props.llm().modelId(),
                        "bundle-v1",
                        "1.0.0",
                        "main",
                        corpusDir,
                        reportPath
                );

                EvalRunner runner = new EvalRunner(evalRepo, corpus, llm);
                EvalRun run = runner.run(spec, config);

                EvalRunRecord runRecord = evalRepo.findRunById(run.runId()).orElse(null);
                String outcome = runRecord != null && runRecord.gateOutcome() != null ? runRecord.gateOutcome() : "PASS";
                String reportJson = String.format("{\"runId\":\"%s\",\"suite\":\"%s\",\"repeats\":%d,\"status\":\"%s\",\"metrics\":%s}",
                        run.runId(), suite, repeats, outcome, run.metrics().toJson());
                if (reportPath.getParent() != null) {
                    Files.createDirectories(reportPath.getParent());
                }
                Files.writeString(reportPath, reportJson);

                if ("blocking".equalsIgnoreCase(gate) && "FAIL".equalsIgnoreCase(outcome)) {
                    log.error("Eval blocking gate failed for run {}", run.runId());
                    return PipelineOutcome.EXIT_ESCALATED;
                }
                return PipelineOutcome.EXIT_SUCCESS;

            } finally {
                if (db != null) {
                    db.close();
                }
            }
        }
    }

    /**
     * {@code reconcile} subcommand: runs scheduled background sweep repairing orphaned leases and unconfirmed effects (§7.7).
     */
    @Command(name = "reconcile", description = "Reconcile orphaned leases and pending external effects", mixinStandardHelpOptions = true)
    public static class ReconcileCommand implements Callable<Integer> {

        @Option(names = {"--db-url"}, defaultValue = "${env:TESTGEN_DB_URL:-}", description = "PostgreSQL JDBC URL")
        private String dbUrl;

        @Option(names = {"--db-user"}, defaultValue = "${env:TESTGEN_DB_USER:-testgen}", description = "PostgreSQL user")
        private String dbUser = "testgen";

        @Option(names = {"--db-password"}, defaultValue = "${env:TESTGEN_DB_PASSWORD:-testgen}", description = "PostgreSQL password")
        private String dbPassword = "testgen";

        @Option(names = {"--orphan-lease-minutes"}, defaultValue = "45", description = "Orphaned lease threshold minutes")
        private long orphanMinutes = 45;

        @Option(names = {"--pending-effect-minutes"}, defaultValue = "30", description = "Pending effect grace threshold minutes")
        private long pendingMinutes = 30;

        @Option(names = {"--gitlab-url"}, defaultValue = "${env:GITLAB_URL:-}", description = "GitLab API URL")
        private String gitlabUrl;

        @Option(names = {"--gitlab-token"}, defaultValue = "${env:GITLAB_TOKEN:-}", description = "GitLab API token")
        private String gitlabToken;

        @Option(names = {"--gitlab-project-id"}, defaultValue = "${env:GITLAB_PROJECT_ID:-}", description = "GitLab project ID or path")
        private String gitlabProjectId;

        @Override
        public Integer call() throws Exception {
            Db db = null;
            JobRepository jobRepo;
            EffectRepository effectRepo;

            if (dbUrl != null && !dbUrl.isBlank()) {
                try {
                    db = Db.create(dbUrl, dbUser, dbPassword);
                    jobRepo = new JdbiJobRepository(db.jdbi());
                    effectRepo = new JdbiEffectRepository(db.jdbi());
                } catch (Exception e) {
                    log.error("Failed to connect to DB for reconciliation: {}", e.getMessage());
                    return PipelineOutcome.EXIT_INFRA_FAILURE;
                }
            } else {
                jobRepo = new InMemoryJobRepository();
                effectRepo = new InMemoryEffectRepository();
            }

            try {
                GitLabIntegrationService gitLab = null;
                if (gitlabUrl != null && !gitlabUrl.isBlank() && gitlabToken != null && !gitlabToken.isBlank()) {
                    gitLab = new GitLabIntegrationService(gitlabUrl, gitlabToken, gitlabProjectId, new EffectExecutor(effectRepo));
                }

                ReconcilerJob reconciler = new ReconcilerJob(
                        jobRepo,
                        effectRepo,
                        gitLab,
                        msg -> log.warn("OpsAlert: {}", msg),
                        Duration.ofMinutes(orphanMinutes),
                        Duration.ofMinutes(pendingMinutes)
                );

                ReconcileReport report = reconciler.run();
                log.info("Reconcile sweep completed: orphans={}, reconciled={}, abandoned={}, violations={}",
                        report.orphanedJobsMarked(), report.pendingEffectsReconciled(),
                        report.pendingEffectsAbandoned(), report.invariantViolations());

                return PipelineOutcome.EXIT_SUCCESS;

            } finally {
                if (db != null) {
                    db.close();
                }
            }
        }
    }

    /**
     * {@code migrate} subcommand: applies database Flyway migrations (§6.14, §9.7).
     */
    @Command(name = "migrate", description = "Run Flyway database migrations", mixinStandardHelpOptions = true)
    public static class MigrateCommand implements Callable<Integer> {

        @Option(names = {"-url", "--url"}, defaultValue = "${env:TESTGEN_DB_URL:-jdbc:postgresql://localhost:5432/testgen}", description = "PostgreSQL JDBC URL")
        private String url = "jdbc:postgresql://localhost:5432/testgen";

        @Option(names = {"-user", "--user"}, defaultValue = "${env:TESTGEN_DB_USER:-testgen}", description = "PostgreSQL username")
        private String user = "testgen";

        @Option(names = {"-password", "--password"}, defaultValue = "${env:TESTGEN_DB_PASSWORD:-testgen}", description = "PostgreSQL password")
        private String password = "testgen";

        @Override
        public Integer call() {
            log.info("Applying database migrations on {}", url);
            try (Db db = Db.create(url, user, password)) {
                db.migrate();
                log.info("Database migrations applied successfully.");
                return PipelineOutcome.EXIT_SUCCESS;
            } catch (RuntimeException e) {
                log.error("Migration failed: {}", e.getMessage(), e);
                return PipelineOutcome.EXIT_INFRA_FAILURE; // Exit 40: Infrastructure failure
            }
        }
    }

    /**
     * Picocli exception handler mapping unhandled exceptions to Section 9.2 exit codes.
     */
    private static class ExitCodeExceptionHandler implements IExecutionExceptionHandler {
        @Override
        public int handleExecutionException(Exception ex, CommandLine commandLine, CommandLine.ParseResult parseResult) {
            log.error("Execution halted by exception: {}", ex.getMessage(), ex);

            if (ex instanceof LeaseLostException) {
                return PipelineOutcome.EXIT_LEASE_LOST; // 41
            }
            if (ex instanceof BudgetExceededException) {
                return PipelineOutcome.EXIT_BUDGET_EXCEEDED; // 50
            }
            if (ex instanceof RedactionFailedException) {
                return PipelineOutcome.EXIT_REDACTION_FAILURE; // 60
            }
            if (ex instanceof IllegalArgumentException) {
                return PipelineOutcome.EXIT_PRECONDITION_FAILURE; // 30
            }
            if (ex instanceof SQLException || ex instanceof GitLabApiException) {
                return PipelineOutcome.EXIT_INFRA_FAILURE; // 40
            }
            if (ex instanceof IllegalStateException) {
                return PipelineOutcome.EXIT_CONFIG_ERROR; // 70
            }

            return PipelineOutcome.EXIT_INFRA_FAILURE; // 40
        }
    }

    /**
     * Picocli parameter error handler mapping CLI syntax/configuration errors to Exit Code 70 (§9.2).
     */
    private static class ConfigErrorParameterHandler implements IParameterExceptionHandler {
        @Override
        public int handleParseException(ParameterException ex, String[] args) {
            CommandLine cmd = ex.getCommandLine();
            PrintWriter err = cmd.getErr();
            err.println(cmd.getColorScheme().errorText(ex.getMessage()));
            CommandLine.UnmatchedArgumentException.printSuggestions(ex, err);
            return PipelineOutcome.EXIT_CONFIG_ERROR; // 70
        }
    }
}
