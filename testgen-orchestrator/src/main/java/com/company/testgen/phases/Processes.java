package com.company.testgen.phases;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Process execution utility for L2 verification (§6.8).
 * Provides fluent execution, OS command resolution (e.g. mvn vs mvn.cmd on Windows),
 * timeout management, stream draining, and infrastructure failure classification.
 */
public final class Processes {

    private static final Logger log = LoggerFactory.getLogger(Processes.class);

    private Processes() {}

    public static Builder exec(String... command) {
        return new Builder(Arrays.asList(command));
    }

    public static Builder exec(List<String> command) {
        return new Builder(command);
    }

    public static class Builder {
        private final List<String> command;
        private Path workingDir;
        private final Map<String, String> env = new HashMap<>();
        private Duration timeout = Duration.ofMinutes(5);

        public Builder(List<String> command) {
            this.command = new ArrayList<>(command);
        }

        public Builder workingDir(Path workingDir) {
            this.workingDir = workingDir;
            return this;
        }

        public Builder env(String key, String value) {
            if (key != null && value != null) {
                this.env.put(key, value);
            }
            return this;
        }

        public Builder timeout(Duration timeout) {
            if (timeout != null) {
                this.timeout = timeout;
            }
            return this;
        }

        public ProcessResult run() {
            return execute(command, workingDir, env, timeout);
        }
    }

    public static ProcessResult execute(List<String> cmd, Path workingDir, Map<String, String> env, Duration timeout) {
        long start = System.currentTimeMillis();
        List<String> adjustedCmd = resolveCommandForOs(cmd, workingDir);

        ProcessBuilder pb = new ProcessBuilder(adjustedCmd);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }

        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("Failed to start process {}: {}", adjustedCmd, e.getMessage());
            return new ProcessResult(1, (int) durationMs, false, true, "", "Failed to start process: " + e.getMessage());
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        ExecutorService ioPool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "process-io-drainer");
            t.setDaemon(true);
            return t;
        });

        Future<?> outFuture = ioPool.submit(() -> drainStream(process.getInputStream(), stdout));
        Future<?> errFuture = ioPool.submit(() -> drainStream(process.getErrorStream(), stderr));

        boolean finished;
        long timeoutMs = timeout != null ? timeout.toMillis() : 300_000L;
        try {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }

        long durationMs = System.currentTimeMillis() - start;
        if (!finished) {
            log.warn("Process {} exceeded timeout of {}ms. Terminating forcibly...", adjustedCmd, timeoutMs);
            process.destroyForcibly();
            ioPool.shutdownNow();
            return new ProcessResult(-1, (int) durationMs, true, false, stdout.toString(), stderr.toString());
        }

        try {
            outFuture.get(1, TimeUnit.SECONDS);
            errFuture.get(1, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            ioPool.shutdown();
        }

        int exitCode = process.exitValue();
        String outStr = stdout.toString();
        String errStr = stderr.toString();
        boolean infra = isInfraFailure(exitCode, outStr, errStr);

        return new ProcessResult(exitCode, (int) durationMs, false, infra, outStr, errStr);
    }

    public static boolean isInfraFailure(int exitCode, String stdout, String stderr) {
        if (exitCode == 0) return false;
        String combined = ((stdout != null ? stdout : "") + " " + (stderr != null ? stderr : "")).toLowerCase(Locale.ROOT);
        return combined.contains("connection refused")
                || combined.contains("unknownhostexception")
                || combined.contains("failed to resolve host")
                || combined.contains("network is unreachable")
                || combined.contains("connection timed out")
                || combined.contains("502 bad gateway")
                || combined.contains("503 service unavailable")
                || combined.contains("env_unavailable")
                || combined.contains("docker is not running")
                || combined.contains("could not transfer artifact")
                || combined.contains("failed to read artifact descriptor")
                || exitCode == 127 || exitCode == 126;
    }

    private static List<String> resolveCommandForOs(List<String> cmd, Path workingDir) {
        if (cmd == null || cmd.isEmpty()) {
            return List.of();
        }
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (!isWindows) {
            return cmd;
        }

        String program = cmd.getFirst();
        List<String> adjusted = new ArrayList<>(cmd);

        if ("mvn".equalsIgnoreCase(program)) {
            // Check if mvnw.cmd is present in working directory
            if (workingDir != null && Files.isRegularFile(workingDir.resolve("mvnw.cmd"))) {
                adjusted.set(0, workingDir.resolve("mvnw.cmd").toAbsolutePath().toString());
                return adjusted;
            }
            // On Windows, run via cmd.exe /c mvn to ensure .cmd batch files resolve properly
            List<String> wrapped = new ArrayList<>();
            wrapped.add("cmd.exe");
            wrapped.add("/c");
            wrapped.addAll(cmd);
            return wrapped;
        }

        return cmd;
    }

    private static void drainStream(InputStream in, StringBuilder out) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append(System.lineSeparator());
            }
        } catch (Exception ignored) {
        }
    }
}
