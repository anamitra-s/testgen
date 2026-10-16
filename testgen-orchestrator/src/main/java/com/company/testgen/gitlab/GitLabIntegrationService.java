package com.company.testgen.gitlab;

import com.company.testgen.idempotency.EffectExecutor;
import com.company.testgen.idempotency.EffectType;
import com.company.testgen.idempotency.FaultInjector;
import com.company.testgen.idempotency.GitLabLookup;
import com.company.testgen.model.ConfidenceTier;
import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.GeneratedFile;
import com.company.testgen.model.RedactionReport;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.model.TokenUsage;
import com.company.testgen.model.ValidationResult;
import com.company.testgen.model.VerificationResult;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Branch;
import org.gitlab4j.api.models.Commit;
import org.gitlab4j.api.models.CommitAction;
import org.gitlab4j.api.models.CommitPayload;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.MergeRequestParams;
import org.gitlab4j.models.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * GitLab API integration service managing branches, commits, and merge requests (§6.15, §7.5).
 * All side-effecting operations are executed strictly through {@link EffectExecutor#exactlyOnce}.
 * Implements {@link GitLabLookup} for background reconciler sweeps (§7.7).
 * Supports local file sink mode (§9.8, §9.9).
 */
public class GitLabIntegrationService implements GitLabLookup {

    private static final Logger log = LoggerFactory.getLogger(GitLabIntegrationService.class);

    private final GitLabApi api;
    private final Object projectId;
    private final EffectExecutor effects;
    private final Path localOutputDir;

    public GitLabIntegrationService(GitLabApi api, Object projectId, EffectExecutor effects) {
        this.api = Objects.requireNonNull(api, "api cannot be null");
        this.projectId = Objects.requireNonNull(projectId, "projectId cannot be null");
        this.effects = Objects.requireNonNull(effects, "effects cannot be null");
        this.localOutputDir = null;
    }

    public GitLabIntegrationService(String gitlabUrl, String apiToken, Object projectId, EffectExecutor effects) {
        this(new GitLabApi(Objects.requireNonNull(gitlabUrl, "gitlabUrl cannot be null"),
                Objects.requireNonNull(apiToken, "apiToken cannot be null")), projectId, effects);
    }

    public GitLabIntegrationService(EffectExecutor effects, Path localOutputDir) {
        this.api = null;
        this.projectId = "local";
        this.effects = Objects.requireNonNull(effects, "effects cannot be null");
        this.localOutputDir = localOutputDir != null ? localOutputDir : Path.of("./local-output");
    }

    public GitLabIntegrationService(EffectExecutor effects) {
        this(effects, Path.of("./local-output"));
    }

    public boolean isLocalMode() {
        return localOutputDir != null;
    }

    /**
     * Publishes a completed test generation job to GitLab (§6.15).
     * Creates branch, commits files, and creates standard Merge Request with exactly-once guarantees.
     */
    public MergeRequestResult publish(
            TestGenerationJob job,
            List<GeneratedFile> files,
            ConfidenceTier tier,
            VerificationResult verify
    ) {
        return publish(job, files, tier, verify, null, null, null, null, null, 1, BigDecimal.ZERO);
    }

    /**
     * Publishes a completed test generation job to GitLab with full metadata report (§6.15).
     */
    public MergeRequestResult publish(
            TestGenerationJob job,
            List<GeneratedFile> files,
            ConfidenceTier tier,
            VerificationResult verify,
            ResolvedLocatorSet locators,
            ExistingCodeContext context,
            RedactionReport redaction,
            TokenUsage tokenUsage,
            Duration duration,
            int attempts,
            BigDecimal estimatedCostUsd
    ) {
        Objects.requireNonNull(job, "job cannot be null");
        Objects.requireNonNull(files, "files cannot be null");
        ConfidenceTier effectiveTier = tier != null ? tier : ConfidenceTier.STATIC_ONLY;

        String branch = BranchNamer.forJob(job);
        log.info("Publishing job {} to branch '{}' in project '{}' with tier {}",
                job.id(), branch, projectId, effectiveTier);

        if (localOutputDir != null) {
            // Local file sink: still execute exactly-once via external_effects ledger
            effects.exactlyOnce(
                    job,
                    EffectType.BRANCH,
                    branch,
                    () -> createLocalBranch(job, branch),
                    () -> checkLocalBranch(job, branch)
            );

            FaultInjector.checkpoint("after-branch-creation");

            String contentKey = computeContentKey(files);
            effects.exactlyOnce(
                    job,
                    EffectType.COMMIT,
                    contentKey,
                    () -> commitLocal(job, files, contentKey),
                    () -> checkLocalCommit(job, contentKey)
            );

            return effects.exactlyOnce(
                    job,
                    EffectType.MERGE_REQUEST,
                    branch,
                    () -> createLocalMr(job, effectiveTier, verify, locators, context, redaction, tokenUsage, duration, attempts, estimatedCostUsd),
                    () -> checkLocalMr(job)
            );
        }

        // 1. Exactly-once BRANCH creation
        effects.exactlyOnce(
                job,
                EffectType.BRANCH,
                branch,
                () -> {
                    try {
                        api.getRepositoryApi().createBranch(projectId, branch, "main");
                        log.info("Created branch '{}' from 'main'", branch);
                        return branch;
                    } catch (GitLabApiException e) {
                        if (branchExists(branch)) {
                            log.info("Branch '{}' already exists in GitLab", branch);
                            return branch;
                        }
                        throw new RuntimeException("Failed to create GitLab branch " + branch + ": " + e.getMessage(), e);
                    }
                },
                () -> branchExists(branch) ? Optional.of(branch) : Optional.empty()
        );

        FaultInjector.checkpoint("after-branch-creation");

        // 2. Exactly-once COMMIT
        String contentKey = computeContentKey(files);
        effects.exactlyOnce(
                job,
                EffectType.COMMIT,
                contentKey,
                () -> commit(branch, job, files, contentKey),
                () -> findCommitByTree(branch, contentKey)
        );

        // 3. Exactly-once MERGE_REQUEST
        return effects.exactlyOnce(
                job,
                EffectType.MERGE_REQUEST,
                branch,
                () -> createMr(job, branch, effectiveTier, verify, locators, context, redaction, tokenUsage, duration, attempts, estimatedCostUsd),
                () -> findOpenMrBySourceBranch(branch)
        );
    }

    /**
     * Publishes an escalated Draft MR when validation or verification fails (§6.15, §7.5).
     */
    public MergeRequestResult publishEscalation(
            TestGenerationJob job,
            List<GeneratedFile> files,
            ConfidenceTier tier,
            String reasonCode,
            ResolvedLocatorSet accepted,
            ValidationResult validation,
            int attemptsUsed
    ) {
        Objects.requireNonNull(job, "job cannot be null");
        String branch = BranchNamer.forJob(job);
        ConfidenceTier effectiveTier = tier != null ? tier : ConfidenceTier.STATIC_ONLY;

        log.info("Publishing ESCALATION draft MR for job {} on branch '{}' (reason={})",
                job.id(), branch, reasonCode);

        List<GeneratedFile> filesToCommit = (files != null && !files.isEmpty())
                ? files
                : List.of(new GeneratedFile(
                        "src/test/resources/testgen/" + job.targetArea() + "-escalation.md",
                        MergeRequestTemplate.renderEscalation(job, effectiveTier, reasonCode, accepted, validation, attemptsUsed),
                        true));

        if (localOutputDir != null) {
            effects.exactlyOnce(
                    job,
                    EffectType.BRANCH,
                    branch,
                    () -> createLocalBranch(job, branch),
                    () -> checkLocalBranch(job, branch)
            );

            FaultInjector.checkpoint("after-branch-creation");

            String contentKey = computeContentKey(filesToCommit);
            effects.exactlyOnce(
                    job,
                    EffectType.COMMIT,
                    contentKey,
                    () -> commitLocal(job, filesToCommit, contentKey),
                    () -> checkLocalCommit(job, contentKey)
            );

            return effects.exactlyOnce(
                    job,
                    EffectType.MERGE_REQUEST,
                    branch,
                    () -> createLocalDraftMr(job, effectiveTier, reasonCode, accepted, validation, attemptsUsed),
                    () -> checkLocalMr(job)
            );
        }

        // 1. Exactly-once BRANCH creation
        effects.exactlyOnce(
                job,
                EffectType.BRANCH,
                branch,
                () -> {
                    try {
                        api.getRepositoryApi().createBranch(projectId, branch, "main");
                        return branch;
                    } catch (GitLabApiException e) {
                        if (branchExists(branch)) {
                            return branch;
                        }
                        throw new RuntimeException("Failed to create GitLab branch " + branch + ": " + e.getMessage(), e);
                    }
                },
                () -> branchExists(branch) ? Optional.of(branch) : Optional.empty()
        );

        FaultInjector.checkpoint("after-branch-creation");

        String contentKey = computeContentKey(filesToCommit);
        effects.exactlyOnce(
                job,
                EffectType.COMMIT,
                contentKey,
                () -> commit(branch, job, filesToCommit, contentKey),
                () -> findCommitByTree(branch, contentKey)
        );

        // 3. Exactly-once Draft MR
        return effects.exactlyOnce(
                job,
                EffectType.MERGE_REQUEST,
                branch,
                () -> createDraftMr(job, branch, effectiveTier, reasonCode, accepted, validation, attemptsUsed),
                () -> findOpenMrBySourceBranch(branch)
        );
    }

    /**
     * Checks if a branch exists in GitLab (§6.15).
     */
    public boolean branchExists(String branch) {
        if (localOutputDir != null) {
            return true;
        }
        try {
            return api.getRepositoryApi().getOptionalBranch(projectId, branch).isPresent();
        } catch (Exception e) {
            log.warn("Error checking branch existence for '{}': {}", branch, e.getMessage());
            return false;
        }
    }

    /**
     * Searches for an existing commit on the specified branch with the matching contentKey (§6.15).
     */
    public Optional<String> findCommitByTree(String branch, String contentKey) {
        if (localOutputDir != null) {
            return Optional.of("local-commit-" + (contentKey.length() > 8 ? contentKey.substring(0, 8) : contentKey));
        }
        try {
            List<Commit> commits = api.getCommitsApi().getCommits(projectId, branch, null, null);
            if (commits != null) {
                for (Commit c : commits) {
                    if (c.getMessage() != null && c.getMessage().contains(contentKey)) {
                        return Optional.of(c.getId());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error searching commits on branch '{}' for contentKey '{}': {}", branch, contentKey, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Finds an open merge request matching the source branch (§6.15).
     */
    public Optional<MergeRequestResult> findOpenMrBySourceBranch(String branch) {
        if (localOutputDir != null) {
            Path mrFile = localOutputDir.resolve("MR-DESCRIPTION.md");
            return Optional.of(new MergeRequestResult(1, "file://" + mrFile.toAbsolutePath().normalize().toString().replace('\\', '/')));
        }
        try {
            List<MergeRequest> mrs = api.getMergeRequestApi().getMergeRequests(projectId, Constants.MergeRequestState.OPENED);
            if (mrs != null) {
                for (MergeRequest mr : mrs) {
                    if (branch.equals(mr.getSourceBranch())) {
                        Integer iid = mr.getIid() != null ? mr.getIid().intValue() : null;
                        return Optional.of(new MergeRequestResult(iid, mr.getWebUrl()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error finding open MR for source branch '{}': {}", branch, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Queries the HEAD commit SHA of the specified branch (§6.13, FR-27).
     */
    public String headSha(String branch) {
        if (localOutputDir != null) {
            return "local-head-sha";
        }
        try {
            Branch b = api.getRepositoryApi().getBranch(projectId, branch);
            if (b != null && b.getCommit() != null) {
                return b.getCommit().getId();
            }
            throw new IllegalStateException("Branch " + branch + " not found or has no commit in project " + projectId);
        } catch (GitLabApiException e) {
            throw new RuntimeException("Failed to get HEAD SHA for branch " + branch + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> lookup(EffectType effectType, String effectKey) {
        if (effectType == null || effectKey == null) {
            return Optional.empty();
        }
        return switch (effectType) {
            case BRANCH -> branchExists(effectKey) ? Optional.of(effectKey) : Optional.empty();
            case COMMIT -> findCommitByTree("main", effectKey);
            case MERGE_REQUEST -> findOpenMrBySourceBranch(effectKey).map(MergeRequestResult::webUrl);
            case SLACK -> Optional.empty();
        };
    }

    private String createLocalBranch(TestGenerationJob job, String branch) {
        Path jobDir = localOutputDir.resolve(job.id().toString());
        try {
            Files.createDirectories(jobDir);
            log.info("Local mode: created branch directory '{}' for branch '{}'", jobDir, branch);
            return branch;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create local branch directory " + jobDir, e);
        }
    }

    private Optional<String> checkLocalBranch(TestGenerationJob job, String branch) {
        Path jobDir = localOutputDir.resolve(job.id().toString());
        return Files.isDirectory(jobDir) ? Optional.of(branch) : Optional.empty();
    }

    private String commitLocal(TestGenerationJob job, List<GeneratedFile> files, String contentKey) {
        Path jobDir = localOutputDir.resolve(job.id().toString());
        try {
            Files.createDirectories(jobDir);
            for (GeneratedFile f : files) {
                if (f.path() != null && f.content() != null) {
                    Path targetFile = jobDir.resolve(f.path());
                    if (targetFile.getParent() != null) {
                        Files.createDirectories(targetFile.getParent());
                    }
                    Files.writeString(targetFile, f.content(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write local generated files into " + jobDir, e);
        }
        String commitSha = "local-commit-" + (contentKey.length() > 8 ? contentKey.substring(0, 8) : contentKey);
        log.info("Local mode: wrote {} files to '{}' (commitSha={})", files.size(), jobDir, commitSha);
        return commitSha;
    }

    private Optional<String> checkLocalCommit(TestGenerationJob job, String contentKey) {
        Path jobDir = localOutputDir.resolve(job.id().toString());
        return Files.isDirectory(jobDir)
                ? Optional.of("local-commit-" + (contentKey.length() > 8 ? contentKey.substring(0, 8) : contentKey))
                : Optional.empty();
    }

    private MergeRequestResult createLocalMr(
            TestGenerationJob job,
            ConfidenceTier tier,
            VerificationResult verify,
            ResolvedLocatorSet locators,
            ExistingCodeContext context,
            RedactionReport redaction,
            TokenUsage tokenUsage,
            Duration duration,
            int attempts,
            BigDecimal estimatedCostUsd
    ) {
        Path jobDir = localOutputDir.resolve(job.id().toString());
        String desc = MergeRequestTemplate.render(
                job, tier, verify, locators, context, redaction, tokenUsage, duration, attempts, estimatedCostUsd);
        Path mrFile = jobDir.resolve("MR-DESCRIPTION.md");
        try {
            Files.createDirectories(jobDir);
            Files.writeString(mrFile, desc, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write MR-DESCRIPTION.md to " + mrFile, e);
        }
        String webUrl = "file://" + mrFile.toAbsolutePath().normalize().toString().replace('\\', '/');
        log.info("Local mode: rendered MR-DESCRIPTION.md to '{}'", mrFile);
        return new MergeRequestResult(1, webUrl);
    }

    private MergeRequestResult createLocalDraftMr(
            TestGenerationJob job,
            ConfidenceTier tier,
            String reasonCode,
            ResolvedLocatorSet accepted,
            ValidationResult validation,
            int attemptsUsed
    ) {
        Path jobDir = localOutputDir.resolve(job.id().toString());
        String desc = MergeRequestTemplate.renderEscalation(job, tier, reasonCode, accepted, validation, attemptsUsed);
        Path mrFile = jobDir.resolve("MR-DESCRIPTION.md");
        try {
            Files.createDirectories(jobDir);
            Files.writeString(mrFile, desc, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write MR-DESCRIPTION.md to " + mrFile, e);
        }
        String webUrl = "file://" + mrFile.toAbsolutePath().normalize().toString().replace('\\', '/');
        log.info("Local mode: rendered Draft Escalation MR-DESCRIPTION.md to '{}'", mrFile);
        return new MergeRequestResult(1, webUrl);
    }

    private Optional<MergeRequestResult> checkLocalMr(TestGenerationJob job) {
        if (job == null) return Optional.empty();
        Path mrFile = localOutputDir.resolve(job.id().toString()).resolve("MR-DESCRIPTION.md");
        if (Files.exists(mrFile)) {
            String webUrl = "file://" + mrFile.toAbsolutePath().normalize().toString().replace('\\', '/');
            return Optional.of(new MergeRequestResult(1, webUrl));
        }
        return Optional.empty();
    }

    private String commit(String branch, TestGenerationJob job, List<GeneratedFile> files, String contentKey) {
        try {
            List<CommitAction> actions = new ArrayList<>();
            for (GeneratedFile f : files) {
                if (f.path() != null && f.content() != null) {
                    actions.add(new CommitAction()
                            .withAction(CommitAction.Action.CREATE)
                            .withFilePath(f.path())
                            .withContent(f.content()));
                }
            }

            String commitMessage = "testgen: " + job.testDescription()
                    + "\n\nTestGen-Job: " + job.id()
                    + "\nTestGen-Tree-Sha: " + contentKey;

            CommitPayload payload = new CommitPayload()
                    .withBranch(branch)
                    .withCommitMessage(commitMessage)
                    .withAuthorName("TestGen Bot")
                    .withAuthorEmail("testgen-bot@company.com")
                    .withActions(actions);

            Commit created = api.getCommitsApi().createCommit(projectId, payload);
            log.info("Created commit {} on branch '{}'", created.getId(), branch);
            return created.getId();
        } catch (GitLabApiException e) {
            throw new RuntimeException("Failed to create commit on branch " + branch + ": " + e.getMessage(), e);
        }
    }

    private MergeRequestResult createMr(
            TestGenerationJob job,
            String branch,
            ConfidenceTier tier,
            VerificationResult verify,
            ResolvedLocatorSet locators,
            ExistingCodeContext context,
            RedactionReport redaction,
            TokenUsage tokenUsage,
            Duration duration,
            int attempts,
            BigDecimal estimatedCostUsd
    ) {
        try {
            String title = MergeRequestTemplate.title(job, tier);
            String desc = MergeRequestTemplate.render(
                    job, tier, verify, locators, context, redaction, tokenUsage, duration, attempts, estimatedCostUsd);

            MergeRequestParams params = new MergeRequestParams()
                    .withSourceBranch(branch)
                    .withTargetBranch("main")
                    .withTitle(title)
                    .withDescription(desc)
                    .withRemoveSourceBranch(true)
                    .withSquash(true)
                    .withLabels(List.of("auto-generated", "testgen", tier.name().toLowerCase()));

            Long assigneeId = resolveAssigneeId(job.requester());
            if (assigneeId != null) {
                params.withAssigneeId(assigneeId);
            }

            MergeRequest mr = api.getMergeRequestApi().createMergeRequest(projectId, params);
            Integer iid = mr.getIid() != null ? mr.getIid().intValue() : null;
            log.info("Successfully created MR !{} ({}) for branch '{}'", iid, mr.getWebUrl(), branch);
            return new MergeRequestResult(iid, mr.getWebUrl());
        } catch (GitLabApiException e) {
            throw new RuntimeException("Failed to create GitLab MR for branch " + branch + ": " + e.getMessage(), e);
        }
    }

    private MergeRequestResult createDraftMr(
            TestGenerationJob job,
            String branch,
            ConfidenceTier tier,
            String reasonCode,
            ResolvedLocatorSet accepted,
            ValidationResult validation,
            int attemptsUsed
    ) {
        try {
            String title = MergeRequestTemplate.draftTitle(job, tier);
            String desc = MergeRequestTemplate.renderEscalation(job, tier, reasonCode, accepted, validation, attemptsUsed);

            MergeRequestParams params = new MergeRequestParams()
                    .withSourceBranch(branch)
                    .withTargetBranch("main")
                    .withTitle(title)
                    .withDescription(desc)
                    .withRemoveSourceBranch(true)
                    .withSquash(true)
                    .withLabels(List.of("auto-generated", "testgen", "draft", "needs-review"));

            Long assigneeId = resolveAssigneeId(job.requester());
            if (assigneeId != null) {
                params.withAssigneeId(assigneeId);
            }

            MergeRequest mr = api.getMergeRequestApi().createMergeRequest(projectId, params);
            Integer iid = mr.getIid() != null ? mr.getIid().intValue() : null;
            log.info("Successfully created Draft Escalation MR !{} ({}) for branch '{}'", iid, mr.getWebUrl(), branch);
            return new MergeRequestResult(iid, mr.getWebUrl());
        } catch (GitLabApiException e) {
            throw new RuntimeException("Failed to create Draft Escalation MR for branch " + branch + ": " + e.getMessage(), e);
        }
    }

    public static String computeContentKey(List<GeneratedFile> files) {
        if (files == null || files.isEmpty()) {
            return "empty";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            List<GeneratedFile> sorted = files.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(GeneratedFile::path, Comparator.nullsFirst(String::compareTo)))
                    .toList();
            for (GeneratedFile f : sorted) {
                if (f.path() != null) {
                    md.update(f.path().getBytes(StandardCharsets.UTF_8));
                }
                if (f.content() != null) {
                    md.update(f.content().getBytes(StandardCharsets.UTF_8));
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private Long resolveAssigneeId(String requester) {
        if (requester == null || requester.isBlank() || "jenkins".equalsIgnoreCase(requester.trim())) {
            return null;
        }
        String trimmed = requester.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            // Requester is a username string, resolve via GitLab UserApi
        }

        if (api == null) {
            return null;
        }

        try {
            Optional<org.gitlab4j.api.models.User> userOpt = api.getUserApi().getOptionalUser(trimmed);
            if (userOpt.isPresent()) {
                return userOpt.get().getId();
            } else {
                log.warn("GitLab user not found for requester '{}', opening MR unassigned", trimmed);
                return null;
            }
        } catch (Exception e) {
            log.warn("Failed to lookup GitLab user for requester '{}' ({}): opening MR unassigned",
                    trimmed, e.getMessage());
            return null;
        }
    }

    public GitLabApi api() {
        return api;
    }

    public Object projectId() {
        return projectId;
    }

    public EffectExecutor effects() {
        return effects;
    }

    public Path localOutputDir() {
        return localOutputDir;
    }
}
