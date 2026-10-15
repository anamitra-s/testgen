package com.company.testgen.gitlab;

import com.company.testgen.model.TestGenerationJob;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Generates deterministic branch names for test generation jobs (§6.15, §7.5).
 * Format: {@code testgen/<area>/<jobId>}
 */
public final class BranchNamer {

    private static final String PREFIX = "testgen";

    private BranchNamer() {}

    public static String forJob(TestGenerationJob job) {
        Objects.requireNonNull(job, "job cannot be null");
        return forJob(job.targetArea(), job.id());
    }

    public static String forJob(String targetArea, UUID jobId) {
        Objects.requireNonNull(targetArea, "targetArea cannot be null");
        Objects.requireNonNull(jobId, "jobId cannot be null");
        String cleanArea = targetArea.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        return PREFIX + "/" + cleanArea + "/" + jobId;
    }

    public static String forAreaAndJobId(String targetArea, UUID jobId) {
        return forJob(targetArea, jobId);
    }

    public static Optional<UUID> extractJobId(String branchName) {
        if (branchName == null || !branchName.startsWith(PREFIX + "/")) {
            return Optional.empty();
        }
        int lastSlash = branchName.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash >= branchName.length() - 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(branchName.substring(lastSlash + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static Optional<String> extractArea(String branchName) {
        if (branchName == null || !branchName.startsWith(PREFIX + "/")) {
            return Optional.empty();
        }
        String[] parts = branchName.split("/");
        if (parts.length >= 3) {
            return Optional.of(parts[1]);
        }
        return Optional.empty();
    }
}
