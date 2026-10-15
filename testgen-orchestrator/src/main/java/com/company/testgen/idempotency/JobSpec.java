package com.company.testgen.idempotency;

import com.company.testgen.model.JobRecord;
import com.company.testgen.model.TestGenerationJob;

/**
 * Specification parameters required to initialize a new job row upon claim (§7.3).
 */
public record JobSpec(
        String targetArea,
        String testDescription,
        String requester,
        String recordingBranch,
        String promptBundleHash,
        String modelId,
        String orchestratorVersion,
        String recorderVersion,
        String baseCommitSha
) {
    public static JobSpec from(TestGenerationJob job) {
        return new JobSpec(
                job.targetArea(),
                job.testDescription(),
                job.requester(),
                job.recordingBranch(),
                job.promptBundleHash(),
                job.modelId(),
                job.orchestratorVersion(),
                job.recorderVersion(),
                job.baseCommitSha()
        );
    }

    public static JobSpec from(JobRecord record) {
        return new JobSpec(
                record.targetArea(),
                record.testDescription(),
                record.requester(),
                record.recordingBranch(),
                record.promptBundleHash(),
                record.modelId(),
                record.orchestratorVersion(),
                record.recorderVersion(),
                record.baseCommitSha()
        );
    }
}
