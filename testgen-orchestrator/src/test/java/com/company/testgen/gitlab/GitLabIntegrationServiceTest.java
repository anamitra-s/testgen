package com.company.testgen.gitlab;

import com.company.testgen.idempotency.EffectExecutor;
import com.company.testgen.idempotency.EffectType;
import com.company.testgen.model.ConfidenceTier;
import com.company.testgen.model.GeneratedFile;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.model.ValidationResult;
import com.company.testgen.model.VerificationResult;
import com.company.testgen.persistence.EffectRecord;
import com.company.testgen.persistence.EffectStatus;
import com.company.testgen.persistence.InMemoryEffectRepository;
import org.gitlab4j.api.CommitsApi;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.MergeRequestApi;
import org.gitlab4j.api.RepositoryApi;
import org.gitlab4j.api.UserApi;
import org.gitlab4j.api.models.Branch;
import org.gitlab4j.api.models.Commit;
import org.gitlab4j.api.models.CommitPayload;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.MergeRequestParams;
import org.gitlab4j.api.models.User;
import org.gitlab4j.models.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitLabIntegrationServiceTest {

    private GitLabApi gitLabApi;
    private RepositoryApi repositoryApi;
    private CommitsApi commitsApi;
    private MergeRequestApi mergeRequestApi;
    private UserApi userApi;
    private InMemoryEffectRepository effectRepo;
    private EffectExecutor effectExecutor;
    private GitLabIntegrationService service;

    private final String projectId = "test-group/test-repo";

    @BeforeEach
    void setUp() {
        gitLabApi = mock(GitLabApi.class);
        repositoryApi = mock(RepositoryApi.class);
        commitsApi = mock(CommitsApi.class);
        mergeRequestApi = mock(MergeRequestApi.class);
        userApi = mock(UserApi.class);

        when(gitLabApi.getRepositoryApi()).thenReturn(repositoryApi);
        when(gitLabApi.getCommitsApi()).thenReturn(commitsApi);
        when(gitLabApi.getMergeRequestApi()).thenReturn(mergeRequestApi);
        when(gitLabApi.getUserApi()).thenReturn(userApi);

        effectRepo = new InMemoryEffectRepository();
        effectExecutor = new EffectExecutor(effectRepo);
        service = new GitLabIntegrationService(gitLabApi, projectId, effectExecutor);
    }

    @Test
    @DisplayName("Deterministic tree-sha content key is invariant to file order")
    void testComputeContentKeyDeterministic() {
        GeneratedFile f1 = new GeneratedFile("src/test/java/A.java", "class A {}", true);
        GeneratedFile f2 = new GeneratedFile("src/test/java/B.java", "class B {}", true);

        String hash1 = GitLabIntegrationService.computeContentKey(List.of(f1, f2));
        String hash2 = GitLabIntegrationService.computeContentKey(List.of(f2, f1));

        assertThat(hash1).isNotEmpty();
        assertThat(hash1).isEqualTo(hash2);

        GeneratedFile f2Modified = new GeneratedFile("src/test/java/B.java", "class B { int x; }", true);
        String hash3 = GitLabIntegrationService.computeContentKey(List.of(f1, f2Modified));
        assertThat(hash3).isNotEqualTo(hash1);
    }

    @Test
    @DisplayName("publish standard MR executes exactly-once BRANCH, COMMIT, and MERGE_REQUEST effects")
    void testPublishStandardMergeRequest() throws Exception {
        UUID jobId = UUID.randomUUID();
        TestGenerationJob job = new TestGenerationJob(
                jobId, "v2:pub-key", "dashboard", "Dashboard date filter test",
                "123", "scratch", "bundle-1", "claude-sonnet-5",
                "1.0.0", "2.0.0", "sha-base", "worker-1", 1L, Path.of(".")
        );

        String branch = BranchNamer.forJob(job);
        Branch createdBranch = new Branch();
        createdBranch.setName(branch);
        when(repositoryApi.createBranch(projectId, branch, "main")).thenReturn(createdBranch);

        Commit createdCommit = new Commit();
        createdCommit.setId("c0ffee123");
        when(commitsApi.createCommit(any(), any(CommitPayload.class))).thenReturn(createdCommit);

        MergeRequest createdMr = new MergeRequest();
        createdMr.setIid(42L);
        createdMr.setSourceBranch(branch);
        createdMr.setWebUrl("https://gitlab.example.com/mr/42");
        when(mergeRequestApi.createMergeRequest(any(), any(MergeRequestParams.class))).thenReturn(createdMr);
        when(mergeRequestApi.getMergeRequests((Object) projectId, Constants.MergeRequestState.OPENED))
                .thenReturn(List.of(createdMr));

        List<GeneratedFile> files = List.of(new GeneratedFile("src/test/java/Test.java", "code", true));

        MergeRequestResult result = service.publish(
                job, files, ConfidenceTier.VERIFIED, VerificationResult.passed(1000)
        );

        assertThat(result.iid()).isEqualTo(42);
        assertThat(result.webUrl()).isEqualTo("https://gitlab.example.com/mr/42");

        // Verify side effects recorded in EffectRepository
        List<EffectRecord> effects = effectRepo.findByJobId(jobId);
        assertThat(effects.stream().anyMatch(e -> e.effectType() == EffectType.BRANCH && e.status() == EffectStatus.CONFIRMED)).isTrue();
        assertThat(effects.stream().anyMatch(e -> e.effectType() == EffectType.COMMIT && e.status() == EffectStatus.CONFIRMED)).isTrue();
        assertThat(effects.stream().anyMatch(e -> e.effectType() == EffectType.MERGE_REQUEST && e.status() == EffectStatus.CONFIRMED)).isTrue();

        // Calling publish again should be a no-op (cached/reconciled effects, exactly-once)
        MergeRequestResult idempotentResult = service.publish(
                job, files, ConfidenceTier.VERIFIED, VerificationResult.passed(1000)
        );
        assertThat(idempotentResult.iid()).isEqualTo(42);

        // createBranch and createCommit should only be called once
        verify(repositoryApi, times(1)).createBranch(projectId, branch, "main");
        verify(commitsApi, times(1)).createCommit(any(), any(CommitPayload.class));
    }

    @Test
    @DisplayName("publishEscalation creates draft MR with [Draft][NEEDS REVIEW] title prefix")
    void testPublishEscalationDraft() throws Exception {
        UUID jobId = UUID.randomUUID();
        TestGenerationJob job = new TestGenerationJob(
                jobId, "v2:pub-esc", "billing", "Billing failure test",
                "123", "scratch", "bundle-1", "claude-sonnet-5",
                "1.0.0", "2.0.0", "sha-base", "worker-1", 1L, Path.of(".")
        );

        String branch = BranchNamer.forJob(job);
        Branch createdBranch = new Branch();
        createdBranch.setName(branch);
        when(repositoryApi.createBranch(projectId, branch, "main")).thenReturn(createdBranch);

        Commit createdCommit = new Commit();
        createdCommit.setId("c0ffee456");
        when(commitsApi.createCommit(any(), any(CommitPayload.class))).thenReturn(createdCommit);

        MergeRequest createdMr = new MergeRequest();
        createdMr.setIid(99L);
        createdMr.setWebUrl("https://gitlab.example.com/mr/99");
        when(mergeRequestApi.createMergeRequest(any(), any(MergeRequestParams.class))).thenReturn(createdMr);

        MergeRequestResult result = service.publishEscalation(
                job, null, ConfidenceTier.STATIC_ONLY, "VALIDATION_EXHAUSTED",
                ResolvedLocatorSet.empty(), new ValidationResult(List.of()), 3
        );

        assertThat(result.iid()).isEqualTo(99);
        ArgumentCaptor<MergeRequestParams> captor = ArgumentCaptor.forClass(MergeRequestParams.class);
        verify(mergeRequestApi).createMergeRequest(any(), captor.capture());

        MergeRequestParams captured = captor.getValue();
        String title = captured.getForm(true).getFormValues().get("title").getValue().toString();
        String desc = captured.getForm(true).getFormValues().get("description").getValue().toString();

        assertThat(title).contains("[Draft][NEEDS REVIEW]");
        assertThat(desc).contains("NEEDS HUMAN REVIEW");
    }

    @Test
    @DisplayName("GitLab assignee resolution: resolves username to user ID via UserApi")
    void testAssigneeResolutionWithUsername() throws Exception {
        UUID jobId = UUID.randomUUID();
        TestGenerationJob job = new TestGenerationJob(
                jobId, "v2:assignee-user", "dashboard", "Dashboard test",
                "jane.doe", "scratch", "bundle-1", "claude-sonnet-5",
                "1.0.0", "2.0.0", "sha-base", "worker-1", 1L, Path.of(".")
        );

        User resolvedUser = new User();
        resolvedUser.setId(789L);
        when(userApi.getOptionalUser("jane.doe")).thenReturn(Optional.of(resolvedUser));

        String branch = BranchNamer.forJob(job);
        Branch createdBranch = new Branch();
        createdBranch.setName(branch);
        when(repositoryApi.createBranch(projectId, branch, "main")).thenReturn(createdBranch);

        Commit createdCommit = new Commit();
        createdCommit.setId("c0ffee789");
        when(commitsApi.createCommit(any(), any(CommitPayload.class))).thenReturn(createdCommit);

        MergeRequest createdMr = new MergeRequest();
        createdMr.setIid(105L);
        createdMr.setWebUrl("https://gitlab.example.com/mr/105");
        when(mergeRequestApi.createMergeRequest(any(), any(MergeRequestParams.class))).thenReturn(createdMr);

        service.publish(job, List.of(new GeneratedFile("src/test/java/Test.java", "code", true)), ConfidenceTier.VERIFIED, VerificationResult.passed(1000));

        ArgumentCaptor<MergeRequestParams> captor = ArgumentCaptor.forClass(MergeRequestParams.class);
        verify(mergeRequestApi).createMergeRequest(any(), captor.capture());
        MergeRequestParams captured = captor.getValue();
        assertThat(captured.getForm(true).getFormValues().get("assignee_id").getValue().toString()).isEqualTo("789");
    }

    @Test
    @DisplayName("GitLab assignee resolution: fail-open fallback leaves MR unassigned when user not found or lookup fails")
    void testAssigneeResolutionFailOpenFallback() throws Exception {
        UUID jobId = UUID.randomUUID();
        TestGenerationJob job = new TestGenerationJob(
                jobId, "v2:assignee-failopen", "dashboard", "Dashboard test",
                "nonexistent.user", "scratch", "bundle-1", "claude-sonnet-5",
                "1.0.0", "2.0.0", "sha-base", "worker-1", 1L, Path.of(".")
        );

        when(userApi.getOptionalUser("nonexistent.user")).thenReturn(Optional.empty());

        String branch = BranchNamer.forJob(job);
        Branch createdBranch = new Branch();
        createdBranch.setName(branch);
        when(repositoryApi.createBranch(projectId, branch, "main")).thenReturn(createdBranch);

        Commit createdCommit = new Commit();
        createdCommit.setId("c0ffee999");
        when(commitsApi.createCommit(any(), any(CommitPayload.class))).thenReturn(createdCommit);

        MergeRequest createdMr = new MergeRequest();
        createdMr.setIid(106L);
        createdMr.setWebUrl("https://gitlab.example.com/mr/106");
        when(mergeRequestApi.createMergeRequest(any(), any(MergeRequestParams.class))).thenReturn(createdMr);

        // Should not throw, MR still created unassigned
        MergeRequestResult result = service.publish(job, List.of(new GeneratedFile("src/test/java/Test.java", "code", true)), ConfidenceTier.VERIFIED, VerificationResult.passed(1000));
        assertThat(result.iid()).isEqualTo(106);

        ArgumentCaptor<MergeRequestParams> captor = ArgumentCaptor.forClass(MergeRequestParams.class);
        verify(mergeRequestApi).createMergeRequest(any(), captor.capture());
        MergeRequestParams captured = captor.getValue();
        var assigneeVal = captured.getForm(true).getFormValues().get("assignee_id");
        assertThat(assigneeVal == null || assigneeVal.getValue() == null).isTrue();
    }

    @Test
    @DisplayName("GitLabLookup strategy delegates to reconciliation queries")
    void testGitLabLookupReconciliation() throws Exception {
        String branch = "testgen/reports/123";
        Branch b = new Branch();
        b.setName(branch);
        when(repositoryApi.getOptionalBranch(projectId, branch)).thenReturn(Optional.of(b));

        Optional<String> branchResult = service.lookup(EffectType.BRANCH, branch);
        assertThat(branchResult).contains(branch);

        MergeRequest mr = new MergeRequest();
        mr.setSourceBranch(branch);
        mr.setIid(55L);
        mr.setWebUrl("https://gitlab/mr/55");
        when(mergeRequestApi.getMergeRequests((Object) projectId, Constants.MergeRequestState.OPENED))
                .thenReturn(List.of(mr));

        Optional<String> mrResult = service.lookup(EffectType.MERGE_REQUEST, branch);
        assertThat(mrResult).contains("https://gitlab/mr/55");
    }
}
