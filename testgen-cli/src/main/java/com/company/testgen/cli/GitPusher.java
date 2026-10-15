package com.company.testgen.cli;

import java.nio.file.Path;

/**
 * Strategy interface to push a recording directory to a scratch branch over Git (§3.2, §4.1).
 */
@FunctionalInterface
public interface GitPusher {
    void push(Path recordingFolder, String scratchBranch, String remoteUrl) throws Exception;
}
