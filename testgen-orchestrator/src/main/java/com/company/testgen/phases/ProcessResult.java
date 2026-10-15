package com.company.testgen.phases;

/**
 * Result of executing an external process during L2 verification (§6.8).
 */
public record ProcessResult(
        int exitCode,
        int durationMs,
        boolean timedOut,
        boolean isInfraFailure,
        String stdout,
        String stderr
) {
    public ProcessResult {
        if (stdout == null) stdout = "";
        if (stderr == null) stderr = "";
    }

    public static ProcessResult success(int durationMs, String stdout) {
        return new ProcessResult(0, durationMs, false, false, stdout, "");
    }

    public static ProcessResult timedOut(int durationMs) {
        return new ProcessResult(-1, durationMs, true, false, "", "Process execution timed out.");
    }

    public static ProcessResult infraFailure(int durationMs, String error) {
        return new ProcessResult(1, durationMs, false, true, "", error);
    }

    public static ProcessResult failure(int exitCode, int durationMs, String stdout, String stderr) {
        return new ProcessResult(exitCode, durationMs, false, false, stdout, stderr);
    }

    public String tail(int maxLines) {
        String combined = stdout + (stdout.isEmpty() ? "" : "\n") + stderr;
        String[] lines = combined.split("\r?\n");
        if (lines.length <= maxLines) {
            return combined.trim();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - maxLines; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString().trim();
    }
}
