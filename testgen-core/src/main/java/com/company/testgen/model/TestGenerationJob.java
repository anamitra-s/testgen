package com.company.testgen.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * In-memory job representation passed through the orchestrator pipeline (§6.1, §6.2, §6.13, §9.3).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
        Path recordingDir,
        ParsedTestPlan plan,
        String targetEnvironment
) {
    public TestGenerationJob(
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
        this(
                id,
                idempotencyKey,
                targetArea,
                testDescription,
                requester,
                recordingBranch,
                promptBundleHash,
                modelId,
                orchestratorVersion,
                recorderVersion,
                baseCommitSha,
                leaseOwner,
                leaseEpoch,
                recordingDir,
                null,
                "staging"
        );
    }

    public TestGenerationJob withId(UUID newId) {
        return new TestGenerationJob(
                newId,
                idempotencyKey,
                targetArea,
                testDescription,
                requester,
                recordingBranch,
                promptBundleHash,
                modelId,
                orchestratorVersion,
                recorderVersion,
                baseCommitSha,
                leaseOwner,
                leaseEpoch,
                recordingDir,
                plan,
                targetEnvironment
        );
    }

    public TestGenerationJob withPlan(ParsedTestPlan plan) {
        return new TestGenerationJob(
                id,
                idempotencyKey,
                targetArea,
                testDescription,
                requester,
                recordingBranch,
                promptBundleHash,
                modelId,
                orchestratorVersion,
                recorderVersion,
                baseCommitSha,
                leaseOwner,
                leaseEpoch,
                recordingDir,
                plan,
                targetEnvironment
        );
    }

    public TestGenerationJob withLease(String leaseOwner, long leaseEpoch) {
        return new TestGenerationJob(
                id,
                idempotencyKey,
                targetArea,
                testDescription,
                requester,
                recordingBranch,
                promptBundleHash,
                modelId,
                orchestratorVersion,
                recorderVersion,
                baseCommitSha,
                leaseOwner,
                leaseEpoch,
                recordingDir,
                plan,
                targetEnvironment
        );
    }

    public TestGenerationJob withBaseCommitSha(String baseCommitSha) {
        return new TestGenerationJob(
                id,
                idempotencyKey,
                targetArea,
                testDescription,
                requester,
                recordingBranch,
                promptBundleHash,
                modelId,
                orchestratorVersion,
                recorderVersion,
                baseCommitSha,
                leaseOwner,
                leaseEpoch,
                recordingDir,
                plan,
                targetEnvironment
        );
    }

    public String generatedTestClassName() {
        if (targetArea == null || targetArea.isBlank()) {
            return "GeneratedE2ETest";
        }
        String cleanArea = targetArea.replaceAll("[^a-zA-Z0-9]", " ");
        StringBuilder sb = new StringBuilder();
        for (String word : cleanArea.split("\\s+")) {
            if (!word.isBlank()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    sb.append(word.substring(1));
                }
            }
        }
        sb.append("Test");
        return sb.toString();
    }

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
                "jenkins",
                "scratch",
                promptBundleHash,
                modelId,
                "1.0.0",
                "2.0.0",
                "main",
                null,
                0L,
                recordingDir
        );
    }
}
