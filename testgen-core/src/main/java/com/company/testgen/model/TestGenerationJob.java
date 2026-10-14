package com.company.testgen.model;

import java.nio.file.Path;
import java.util.UUID;

/**
 * In-memory job representation passed through the orchestrator pipeline (§6.13, §9.3).
 */
public record TestGenerationJob(
        UUID id,
        String idempotencyKey,
        String targetArea,
        String testDescription,
        String requester,
        String recordingBranch,
        String promptBundleHash,
        String modelId,
        String orchestratorVersion,
        String recorderVersion,
        String baseCommitSha,
        String leaseOwner,
        long leaseEpoch,
        Path recordingDir
) {
    public static TestGenerationJob fromRecord(JobRecord record, Path recordingDir) {
        return new TestGenerationJob(
                record.id(),
                record.idempotencyKey(),
                record.targetArea(),
                record.testDescription(),
                record.requester(),
                record.recordingBranch(),
                record.promptBundleHash(),
                record.modelId(),
                record.orchestratorVersion(),
                record.recorderVersion(),
                record.baseCommitSha(),
                record.leaseOwner(),
                record.leaseEpoch(),
                recordingDir
        );
    }

    public static TestGenerationJob newJob(
            UUID id,
            String idempotencyKey,
            String targetArea,
            String testDescription,
            String promptBundleHash,
            String modelId,
            Path recordingDir
    ) {
        return new TestGenerationJob(
                id,
                idempotencyKey,
                targetArea,
                testDescription,
                "automated",
                "feature/recording-" + targetArea,
                promptBundleHash,
                modelId,
                "1.0.0",
                "2.0.0",
                "HEAD",
                "worker-" + ProcessHandle.current().pid(),
                1L,
                recordingDir
        );
    }
}
