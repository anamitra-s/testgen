package com.company.testgen.idempotency;

import com.company.testgen.gitlab.MergeRequestResult;
import com.company.testgen.model.JobRecord;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.orchestrator.PipelineState;
import com.company.testgen.persistence.EffectRecord;
import com.company.testgen.persistence.EffectStatus;
import com.company.testgen.persistence.InMemoryEffectRepository;
import com.company.testgen.persistence.InMemoryJobRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencySubsystemTest {

    private JobSpec sampleSpec() {
        return new JobSpec(
                "dashboard",
                "Dashboard date filter persists across reload",
                "user-1",
                "scratch/dashboard-date-filter",
                "bundle-hash-123",
                "claude-sonnet-5",
                "1.0.0",
                "2.0.0",
                "base-sha-456"
        );
    }

    @Test
    @DisplayName("IdempotencyGate: fresh claim returns PROCEED with epoch 1")
    void testClaimNewJob() {
        InMemoryJobRepository repo = new InMemoryJobRepository();
        IdempotencyGate gate = new IdempotencyGate(repo, "worker-1", Duration.ofMinutes(30));

        String key = "v2:some-hash-abc";
        Claim claim = gate.claim(key, sampleSpec());

        assertThat(claim.status()).isEqualTo(ClaimStatus.PROCEED);
        assertThat(claim.isProceed()).isTrue();
        assertThat(claim.shouldExecute()).isTrue();
        assertThat(claim.leaseEpoch()).isEqualTo(1L);
        assertThat(claim.exitCode()).isEqualTo(0);

        Optional<JobRecord> record = repo.findById(claim.jobId());
        assertThat(record).isPresent();
        assertThat(record.get().state()).isEqualTo("CLAIMED");
        assertThat(record.get().leaseEpoch()).isEqualTo(1L);
        assertThat(record.get().leaseOwner()).isEqualTo("worker-1");
    }

    @Test
    @DisplayName("IdempotencyGate: active in-flight claim returns ALREADY_RUNNING (exit code 20)")
    void testClaimAlreadyRunning() {
        InMemoryJobRepository repo = new InMemoryJobRepository();
        IdempotencyGate gate = new IdempotencyGate(repo, "worker-1", Duration.ofMinutes(30));

        String key = "v2:some-hash-abc";
        Claim first = gate.claim(key, sampleSpec());
        assertThat(first.status()).isEqualTo(ClaimStatus.PROCEED);

        Claim second = gate.claim(key, sampleSpec());
        assertThat(second.status()).isEqualTo(ClaimStatus.ALREADY_RUNNING);
        assertThat(second.isAlreadyRunning()).isTrue();
        assertThat(second.shouldExecute()).isFalse();
        assertThat(second.exitCode()).isEqualTo(20);
        assertThat(second.leaseOwner()).isEqualTo("worker-1");
        assertThat(second.jobId()).isEqualTo(first.jobId());
    }

    @Test
    @DisplayName("IdempotencyGate: expired lease reclaims with atomic epoch bump and returns RESUME")
    void testClaimExpiredLeaseResumes() {
        InMemoryJobRepository repo = new InMemoryJobRepository();
        IdempotencyGate gate1 = new IdempotencyGate(repo, "worker-1", Duration.ofMinutes(30));

        String key = "v2:some-hash-abc";
        Claim first = gate1.claim(key, sampleSpec());

        // Simulate lease expiration by updating leaseExpiresAt to past
        JobRecord current = repo.findById(first.jobId()).get();
        JobRecord expired = new JobRecord(
                current.id(), current.idempotencyKey(), current.targetArea(), current.testDescription(),
                current.requester(), current.recordingBranch(), "RESOLVED", current.confidenceTier(),
                current.failureReason(), current.promptBundleHash(), current.modelId(), current.orchestratorVersion(),
                current.recorderVersion(), current.baseCommitSha(), current.leaseOwner(), current.leaseEpoch(),
                Instant.now().minus(Duration.ofMinutes(5)), current.buildUrl(), current.redactionReport(), current.mrIid(), current.mrUrl(),
                current.createdAt(), current.updatedAt(), null
        );
        repo.update(expired);

        IdempotencyGate gate2 = new IdempotencyGate(repo, "worker-2", Duration.ofMinutes(30));
        Claim reclaimed = gate2.claim(key, sampleSpec());

        assertThat(reclaimed.status()).isEqualTo(ClaimStatus.RESUME);
        assertThat(reclaimed.isResume()).isTrue();
        assertThat(reclaimed.shouldExecute()).isTrue();
        assertThat(reclaimed.leaseEpoch()).isEqualTo(2L);
        assertThat(reclaimed.state()).isEqualTo("RESOLVED");

        JobRecord updated = repo.findById(first.jobId()).get();
        assertThat(updated.leaseEpoch()).isEqualTo(2L);
        assertThat(updated.leaseOwner()).isEqualTo("worker-2");
    }

    @Test
    @DisplayName("IdempotencyGate: terminal states DONE and NEEDS_HUMAN_REVIEW return ALREADY_COMPLETE (exit code 20)")
    void testClaimAlreadyComplete() {
        InMemoryJobRepository repo = new InMemoryJobRepository();
        IdempotencyGate gate = new IdempotencyGate(repo, "worker-1", Duration.ofMinutes(30));

        String keyDone = "v2:done-job";
        Claim claimDone = gate.claim(keyDone, sampleSpec());
        JobRecord current = repo.findById(claimDone.jobId()).get();
        repo.update(new JobRecord(
                current.id(), current.idempotencyKey(), current.targetArea(), current.testDescription(),
                current.requester(), current.recordingBranch(), "DONE", "VERIFIED",
                null, current.promptBundleHash(), current.modelId(), current.orchestratorVersion(),
                current.recorderVersion(), current.baseCommitSha(), current.leaseOwner(), current.leaseEpoch(),
                current.leaseExpiresAt(), current.buildUrl(), current.redactionReport(), 42, "https://gitlab.example.com/mr/42",
                current.createdAt(), Instant.now(), Instant.now()
        ));

        Claim replayDone = gate.claim(keyDone, sampleSpec());
        assertThat(replayDone.status()).isEqualTo(ClaimStatus.ALREADY_COMPLETE);
        assertThat(replayDone.exitCode()).isEqualTo(20);
        assertThat(replayDone.mrUrl()).isEqualTo("https://gitlab.example.com/mr/42");

        String keyReview = "v2:review-job";
        Claim claimReview = gate.claim(keyReview, sampleSpec());
        JobRecord currentReview = repo.findById(claimReview.jobId()).get();
        repo.update(new JobRecord(
                currentReview.id(), currentReview.idempotencyKey(), currentReview.targetArea(), currentReview.testDescription(),
                currentReview.requester(), currentReview.recordingBranch(), "NEEDS_HUMAN_REVIEW", "STATIC_ONLY",
                "VALIDATION_EXHAUSTED", currentReview.promptBundleHash(), currentReview.modelId(), currentReview.orchestratorVersion(),
                currentReview.recorderVersion(), currentReview.baseCommitSha(), currentReview.leaseOwner(), currentReview.leaseEpoch(),
                currentReview.leaseExpiresAt(), currentReview.buildUrl(), currentReview.redactionReport(), 43, "https://gitlab.example.com/mr/43",
                currentReview.createdAt(), Instant.now(), Instant.now()
        ));

        Claim replayReview = gate.claim(keyReview, sampleSpec());
        assertThat(replayReview.status()).isEqualTo(ClaimStatus.ALREADY_COMPLETE);
        assertThat(replayReview.exitCode()).isEqualTo(20);
        assertThat(replayReview.state()).isEqualTo("NEEDS_HUMAN_REVIEW");
    }

    @Test
    @DisplayName("IdempotencyGate: terminal state FAILED reclaims lease and returns PROCEED")
    void testClaimFailedJobReclaims() {
        InMemoryJobRepository repo = new InMemoryJobRepository();
        IdempotencyGate gate = new IdempotencyGate(repo, "worker-1", Duration.ofMinutes(30));

        String key = "v2:failed-job";
        Claim first = gate.claim(key, sampleSpec());
        repo.markFailed(first.jobId(), "COMPILATION_ERROR");

        IdempotencyGate gate2 = new IdempotencyGate(repo, "worker-2", Duration.ofMinutes(30));
        Claim rerun = gate2.claim(key, sampleSpec());

        assertThat(rerun.status()).isEqualTo(ClaimStatus.PROCEED);
        assertThat(rerun.jobId()).isEqualTo(first.jobId());
        assertThat(rerun.leaseEpoch()).isEqualTo(2L);
    }

    @Test
    @DisplayName("IdempotencyGate: asserts key matches and throws IllegalArgumentException on mismatch")
    void testIdempotencyGateAssertKeyMatches() {
        // Matching key should succeed silently
        IdempotencyGate.assertKeyMatches("v2:key-123", "v2:key-123");
        IdempotencyGate.assertKeyMatches(null, "v2:key-123");
        IdempotencyGate.assertKeyMatches("", "v2:key-123");

        // Mismatched key should throw IllegalArgumentException
        assertThatThrownBy(() -> IdempotencyGate.assertKeyMatches("v2:cli-key", "v2:derived-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mismatch");
    }

    @Test
    @DisplayName("FencedJobWriter: updates state and touches lease when epoch matches")
    void testFencedJobWriterSuccess() {
        InMemoryJobRepository repo = new InMemoryJobRepository();
        UUID jobId = UUID.randomUUID();
        repo.insert(JobRecord.newClaimed(
                jobId, "key-1", "area", "desc", "user", "branch",
                "p", "m", "o", "r", "c", "owner", 1L, Instant.now().plus(Duration.ofMinutes(30))
        ));

        FencedJobWriter writer = new FencedJobWriter(jobId, 1L, repo);
        writer.setState(PipelineState.PARSED);

        assertThat(repo.findById(jobId).get().state()).isEqualTo("PARSED");

        writer.touch();
        assertThat(repo.findById(jobId).get().leaseExpiresAt()).isAfter(Instant.now());

        writer.complete("VERIFIED", 101, "https://mr/101");
        assertThat(repo.findById(jobId).get().state()).isEqualTo("DONE");
        assertThat(repo.findById(jobId).get().mrIid()).isEqualTo(101);
    }

    @Test
    @DisplayName("FencedJobWriter: throws LeaseLostException (exit code 41) when epoch is superseded")
    void testFencedJobWriterLeaseLost() {
        InMemoryJobRepository repo = new InMemoryJobRepository();
        UUID jobId = UUID.randomUUID();
        repo.insert(JobRecord.newClaimed(
                jobId, "key-1", "area", "desc", "user", "branch",
                "p", "m", "o", "r", "c", "owner", 1L, Instant.now().plus(Duration.ofMinutes(30))
        ));

        FencedJobWriter writerZombie = new FencedJobWriter(jobId, 1L, repo);

        // Simulate lease expiration so worker-2 can legitimately reclaim
        JobRecord current = repo.findById(jobId).get();
        repo.update(new JobRecord(
                current.id(), current.idempotencyKey(), current.targetArea(), current.testDescription(),
                current.requester(), current.recordingBranch(), current.state(), current.confidenceTier(),
                current.failureReason(), current.promptBundleHash(), current.modelId(), current.orchestratorVersion(),
                current.recorderVersion(), current.baseCommitSha(), current.leaseOwner(), current.leaseEpoch(),
                Instant.now().minus(Duration.ofMinutes(1)), current.buildUrl(), current.redactionReport(), current.mrIid(), current.mrUrl(),
                current.createdAt(), current.updatedAt(), null
        ));

        // Another worker reclaims the stale lease, incrementing epoch to 2
        Optional<Long> reclaimed = repo.reclaim(jobId, "worker-2", Instant.now().plus(Duration.ofMinutes(30)));
        assertThat(reclaimed).isPresent();
        assertThat(reclaimed.get()).isEqualTo(2L);
        assertThat(repo.findById(jobId).get().leaseEpoch()).isEqualTo(2L);

        assertThatThrownBy(() -> writerZombie.setState(PipelineState.RESOLVING))
                .isInstanceOf(LeaseLostException.class)
                .satisfies(e -> {
                    LeaseLostException lle = (LeaseLostException) e;
                    assertThat(lle.getExitCode()).isEqualTo(41);
                    assertThat(lle.getJobId()).isEqualTo(jobId);
                    assertThat(lle.getLeaseEpoch()).isEqualTo(1L);
                });

        assertThatThrownBy(writerZombie::touch)
                .isInstanceOf(LeaseLostException.class);
    }

    @Test
    @DisplayName("LeaseHeartbeat: periodically touches writer using virtual threads and interrupts main thread on LeaseLostException")
    void testLeaseHeartbeatInterruptsOnLeaseLost() throws Exception {
        InMemoryJobRepository repo = new InMemoryJobRepository();
        UUID jobId = UUID.randomUUID();
        repo.insert(JobRecord.newClaimed(
                jobId, "key-1", "area", "desc", "user", "branch",
                "p", "m", "o", "r", "c", "owner", 1L, Instant.now().plus(Duration.ofMinutes(30))
        ));

        FencedJobWriter writer = new FencedJobWriter(jobId, 1L, repo, Duration.ofMillis(30));
        CountDownLatch onLostLatch = new CountDownLatch(1);
        AtomicBoolean onLostCalled = new AtomicBoolean(false);

        try (LeaseHeartbeat heartbeat = new LeaseHeartbeat(writer, Duration.ofMillis(30), () -> {
            onLostCalled.set(true);
            onLostLatch.countDown();
        })) {
            // Wait for at least one successful heartbeat touch
            Thread.sleep(40);

            // Expire the lease and reclaim with worker-2 (epoch becomes 2)
            JobRecord current = repo.findById(jobId).get();
            repo.update(new JobRecord(
                    current.id(), current.idempotencyKey(), current.targetArea(), current.testDescription(),
                    current.requester(), current.recordingBranch(), current.state(), current.confidenceTier(),
                    current.failureReason(), current.promptBundleHash(), current.modelId(), current.orchestratorVersion(),
                    current.recorderVersion(), current.baseCommitSha(), current.leaseOwner(), current.leaseEpoch(),
                    Instant.now().minus(Duration.ofMinutes(1)), current.buildUrl(), current.redactionReport(), current.mrIid(), current.mrUrl(),
                    current.createdAt(), current.updatedAt(), null
            ));
            Optional<Long> reclaimed = repo.reclaim(jobId, "worker-2", Instant.now().plus(Duration.ofMinutes(30)));
            assertThat(reclaimed).isPresent();
            assertThat(reclaimed.get()).isEqualTo(2L);

            // Next touch should throw LeaseLostException and trigger onLost
            boolean fired = onLostLatch.await(1000, TimeUnit.MILLISECONDS);
            assertThat(fired).isTrue();
            assertThat(onLostCalled.get()).isTrue();
        }
    }

    @Test
    @DisplayName("EffectExecutor: exactlyOnce executes action once, reserves PENDING and confirms")
    void testEffectExecutorSuccess() {
        InMemoryEffectRepository repo = new InMemoryEffectRepository();
        EffectExecutor executor = new EffectExecutor(repo);

        UUID jobId = UUID.randomUUID();
        String branch = "testgen/dashboard/" + jobId;
        AtomicInteger actionInvocations = new AtomicInteger(0);

        String result = executor.exactlyOnce(
                jobId, 1L, EffectType.BRANCH, branch,
                () -> {
                    actionInvocations.incrementAndGet();
                    return "branch-ref-123";
                },
                Optional::empty
        );

        assertThat(result).isEqualTo("branch-ref-123");
        assertThat(actionInvocations.get()).isEqualTo(1);

        Optional<EffectRecord> record = repo.find(jobId, EffectType.BRANCH, branch);
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo(EffectStatus.CONFIRMED);
        assertThat(record.get().externalId()).isEqualTo("branch-ref-123");

        // Second execution: skips action and rehydrates
        String second = executor.exactlyOnce(
                jobId, 1L, EffectType.BRANCH, branch,
                () -> {
                    actionInvocations.incrementAndGet();
                    return "another-branch";
                },
                Optional::empty
        );

        assertThat(second).isEqualTo("branch-ref-123");
        assertThat(actionInvocations.get()).isEqualTo(1); // Not called again!
    }

    @Test
    @DisplayName("EffectExecutor: rejects mutation and throws LeaseLostException when worker lease epoch is stale")
    void testEffectExecutorFencedAgainstLeaseEpoch() {
        InMemoryEffectRepository effectRepo = new InMemoryEffectRepository();
        InMemoryJobRepository jobRepo = new InMemoryJobRepository(effectRepo);
        EffectExecutor executor = new EffectExecutor(effectRepo);

        UUID jobId = UUID.randomUUID();
        jobRepo.insert(JobRecord.newClaimed(
                jobId, "key-fenced", "dashboard", "desc", "user", "branch",
                "bundle", "model", "1.0.0", "1.0.0", "sha", "worker-1", 1L,
                Instant.now().plus(Duration.ofMinutes(30))
        ));

        // Reclaim by worker-2: increments lease_epoch to 2
        JobRecord current = jobRepo.findById(jobId).orElseThrow();
        jobRepo.update(new JobRecord(
                current.id(), current.idempotencyKey(), current.targetArea(), current.testDescription(),
                current.requester(), current.recordingBranch(), current.state(), current.confidenceTier(),
                current.failureReason(), current.promptBundleHash(), current.modelId(), current.orchestratorVersion(),
                current.recorderVersion(), current.baseCommitSha(), current.leaseOwner(), current.leaseEpoch(),
                Instant.now().minus(Duration.ofMinutes(1)), current.buildUrl(), current.redactionReport(), current.mrIid(), current.mrUrl(),
                current.createdAt(), current.updatedAt(), null
        ));
        jobRepo.reclaim(jobId, "worker-2", Instant.now().plus(Duration.ofMinutes(30)));
        assertThat(jobRepo.findById(jobId).get().leaseEpoch()).isEqualTo(2L);

        // Zombie worker with epoch 1 must have effect reservation rejected with LeaseLostException (41)
        assertThatThrownBy(() -> executor.exactlyOnce(
                jobId, 1L, EffectType.BRANCH, "testgen/fenced-branch",
                () -> "branch-sha",
                Optional::empty
        )).isInstanceOf(LeaseLostException.class)
                .satisfies(e -> {
                    LeaseLostException lle = (LeaseLostException) e;
                    assertThat(lle.getExitCode()).isEqualTo(41);
                    assertThat(lle.getJobId()).isEqualTo(jobId);
                });
    }

    @Test
    @DisplayName("EffectExecutor: cleanly rehydrates full typed MergeRequestResult when prior effect is CONFIRMED")
    void testEffectExecutorRehydratesTypedMergeRequestResult() {
        InMemoryEffectRepository effectRepo = new InMemoryEffectRepository();
        EffectExecutor executor = new EffectExecutor(effectRepo);

        UUID jobId = UUID.randomUUID();
        String effectKey = "testgen/dashboard/" + jobId;

        // Simulate confirmed MR effect with serialized MergeRequestResult JSON
        effectRepo.reserve(jobId, EffectType.MERGE_REQUEST, effectKey, 1L);
        effectRepo.confirm(jobId, 1L, EffectType.MERGE_REQUEST, effectKey, "{\"iid\":101,\"webUrl\":\"https://gitlab.example.com/mr/101\"}");

        AtomicInteger actionCalled = new AtomicInteger(0);
        MergeRequestResult result = executor.exactlyOnce(
                jobId, 1L, EffectType.MERGE_REQUEST, effectKey,
                () -> {
                    actionCalled.incrementAndGet();
                    return new MergeRequestResult(999, "https://should.not.be.called");
                },
                Optional::empty
        );

        assertThat(actionCalled.get()).isEqualTo(0);
        assertThat(result).isNotNull();
        assertThat(result.iid()).isEqualTo(101);
        assertThat(result.webUrl()).isEqualTo("https://gitlab.example.com/mr/101");
    }

    @Test
    @DisplayName("EffectExecutor: reconciles PENDING effect against external system if it landed")
    void testEffectExecutorReconcilesLandedPending() {
        InMemoryEffectRepository repo = new InMemoryEffectRepository();
        EffectExecutor executor = new EffectExecutor(repo);

        UUID jobId = UUID.randomUUID();
        String mrKey = "testgen/dashboard/" + jobId;

        // Simulate crash after reserve: row remains PENDING
        repo.reserve(jobId, EffectType.MERGE_REQUEST, mrKey, 1L);
        assertThat(repo.find(jobId, EffectType.MERGE_REQUEST, mrKey).get().status()).isEqualTo(EffectStatus.PENDING);

        AtomicInteger actionCalled = new AtomicInteger(0);
        String mrUrl = executor.exactlyOnce(
                jobId, 1L, EffectType.MERGE_REQUEST, mrKey,
                () -> {
                    actionCalled.incrementAndGet();
                    return "https://gitlab.example.com/mr/99";
                },
                () -> Optional.of("https://gitlab.example.com/mr/88") // External system reports MR was indeed created
        );

        assertThat(mrUrl).isEqualTo("https://gitlab.example.com/mr/88");
        assertThat(actionCalled.get()).isEqualTo(0); // Action was NOT performed

        EffectRecord confirmed = repo.find(jobId, EffectType.MERGE_REQUEST, mrKey).get();
        assertThat(confirmed.status()).isEqualTo(EffectStatus.CONFIRMED);
        assertThat(confirmed.externalId()).isEqualTo("https://gitlab.example.com/mr/88");
    }

    @Test
    @DisplayName("EffectExecutor: retries action when PENDING effect did not land; throws EffectExhaustedException at MAX_EFFECT_ATTEMPTS")
    void testEffectExecutorExhaustion() {
        InMemoryEffectRepository repo = new InMemoryEffectRepository();
        EffectExecutor executor = new EffectExecutor(repo);

        UUID jobId = UUID.randomUUID();
        String effectKey = "commit-" + jobId;

        // Run 1: starts, reserves PENDING, crashes before confirming
        repo.reserve(jobId, EffectType.COMMIT, effectKey, 1L);

        // Run 2: reconcile returns empty -> increments attempts to 1, performs action and crashes again
        executor.exactlyOnce(
                jobId, 1L, EffectType.COMMIT, effectKey,
                () -> "sha-1",
                Optional::empty
        );
        // Force back to PENDING with attempt=1 to simulate crash
        repo.abandon(jobId, EffectType.COMMIT, effectKey, "SIMULATE_PENDING");
        // Re-reserve to test attempt counting
        InMemoryEffectRepository freshRepo = new InMemoryEffectRepository();
        EffectExecutor freshExecutor = new EffectExecutor(freshRepo);
        freshRepo.reserve(jobId, EffectType.COMMIT, effectKey, 1L);

        // Increment attempts to MAX_EFFECT_ATTEMPTS - 1
        freshRepo.incrementAttempts(jobId, EffectType.COMMIT, effectKey);
        freshRepo.incrementAttempts(jobId, EffectType.COMMIT, effectKey);
        assertThat(freshRepo.find(jobId, EffectType.COMMIT, effectKey).get().attempts()).isEqualTo(2);

        // Next call with reconcile empty will increment attempts to 3 (>= MAX) and throw EffectExhaustedException
        assertThatThrownBy(() -> freshExecutor.exactlyOnce(
                jobId, 1L, EffectType.COMMIT, effectKey,
                () -> "sha-2",
                Optional::empty
        )).isInstanceOf(EffectExhaustedException.class);

        assertThat(freshRepo.find(jobId, EffectType.COMMIT, effectKey).get().status())
                .isEqualTo(EffectStatus.ABANDONED);
    }

    @Test
    @DisplayName("ReconcilerJob: marks expired non-terminal jobs as ORPHANED_LEASE, reconciles aged PENDING effects, and alerts invariant violations")
    void testReconcilerJobSweep() {
        InMemoryEffectRepository effectRepo = new InMemoryEffectRepository();
        InMemoryJobRepository jobRepo = new InMemoryJobRepository(effectRepo);

        List<String> alerts = new ArrayList<>();
        OpsAlerter alerter = alerts::add;

        // 1. Setup expired non-terminal job (expired > 45 min ago)
        UUID orphanJobId = UUID.randomUUID();
        jobRepo.insert(new JobRecord(
                orphanJobId, "k1", "dashboard", "desc", "user", "branch",
                "RESOLVED", null, null, "p", "m", "o", "r", "c",
                "dead-worker", 1L, Instant.now().minus(Duration.ofMinutes(50)),
                "http://jenkins/job/1", null, null, null, Instant.now().minus(Duration.ofHours(2)), Instant.now(), null
        ));

        // 2. Setup aged PENDING effect (> 30 min ago)
        UUID effectJobId = UUID.randomUUID();
        effectRepo.reserve(effectJobId, EffectType.BRANCH, "testgen/area/" + effectJobId, 1L);
        // simulate aged created_at by using a custom lookup
        GitLabLookup gitLabLookup = (type, key) -> {
            if (type == EffectType.BRANCH && key.contains(effectJobId.toString())) {
                return Optional.of("branch-sha-reconciled");
            }
            return Optional.empty();
        };

        // 3. Setup invariant violation: DONE job without confirmed MR
        UUID violationJobId = UUID.randomUUID();
        jobRepo.insert(new JobRecord(
                violationJobId, "k2", "dashboard", "desc", "user", "branch",
                "DONE", "VERIFIED", null, "p", "m", "o", "r", "c",
                "worker", 1L, Instant.now().plus(Duration.ofMinutes(20)),
                "http://jenkins/job/2", null, null, null, Instant.now().minus(Duration.ofMinutes(10)), Instant.now(), Instant.now()
        ));

        // Reconciler with 0s duration to immediately consider everything eligible
        ReconcilerJob reconciler = new ReconcilerJob(
                jobRepo, effectRepo, gitLabLookup, alerter,
                Duration.ofMinutes(45), Duration.ZERO
        );

        ReconcileReport report = reconciler.run();

        // Verify orphan marked
        assertThat(report.orphanedJobsMarked()).isEqualTo(1);
        JobRecord updatedOrphan = jobRepo.findById(orphanJobId).get();
        assertThat(updatedOrphan.state()).isEqualTo("FAILED");
        assertThat(updatedOrphan.failureReason()).isEqualTo("ORPHANED_LEASE");

        // Verify effect reconciled
        assertThat(report.pendingEffectsReconciled()).isEqualTo(1);
        EffectRecord reconciledEffect = effectRepo.find(effectJobId, EffectType.BRANCH, "testgen/area/" + effectJobId).get();
        assertThat(reconciledEffect.status()).isEqualTo(EffectStatus.CONFIRMED);
        assertThat(reconciledEffect.externalId()).isEqualTo("branch-sha-reconciled");

        // Verify invariant violation caught
        assertThat(report.invariantViolations()).isEqualTo(1);
        assertThat(report.invariantJobIds()).containsExactly(violationJobId);

        // Verify alerts sent to Ops
        assertThat(alerts).anyMatch(a -> a.contains("orphaned in state RESOLVED"));
        assertThat(alerts).anyMatch(a -> a.contains("http://jenkins/job/1"));
        assertThat(alerts).anyMatch(a -> a.contains("INVARIANT VIOLATION"));
    }
}
