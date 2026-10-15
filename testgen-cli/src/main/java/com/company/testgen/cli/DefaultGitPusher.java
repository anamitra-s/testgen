package com.company.testgen.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Default implementation of {@link GitPusher} executing git over HTTPS via CLI (§3.2).
 */
public class DefaultGitPusher implements GitPusher {

    @Override
    public void push(Path recordingFolder, String scratchBranch, String remoteUrl) throws Exception {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new IllegalArgumentException("Git remote URL is required to push scratch branch");
        }

        ProcessBuilder pb = new ProcessBuilder(
                "git", "push", remoteUrl, "HEAD:refs/heads/" + scratchBranch
        );
        if (recordingFolder != null) {
            pb.directory(recordingFolder.toFile());
        }

        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String errorOutput = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("Git push failed with exit code " + exitCode + ": " + errorOutput);
        }
    }
}
