package com.company.testgen.phases;

import com.company.testgen.config.PipelineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only provider of pre-minted browser session tokens (§6.8, §9.4).
 * Never performs an inline login: reads pre-minted storageState from Vault/filesystem,
 * validates freshness (default maxAge 4h), copies to a short-lived temporary file with 0600 permissions,
 * and ensures tokens never outlive the verification phase.
 */
public class AuthSessionProvider {

    private static final Logger log = LoggerFactory.getLogger(AuthSessionProvider.class);

    public static final Duration DEFAULT_MAX_AGE = Duration.ofHours(4);

    private final Path sessionBaseDir;
    private final Duration maxAge;

    public AuthSessionProvider() {
        this(resolveDefaultSessionDir(), DEFAULT_MAX_AGE);
    }

    public AuthSessionProvider(Path sessionBaseDir) {
        this(sessionBaseDir, DEFAULT_MAX_AGE);
    }

    public AuthSessionProvider(PipelineProperties.SessionProperties props) {
        this(resolveSessionDirFromProps(props), props != null && props.maxAge() != null ? props.maxAge() : DEFAULT_MAX_AGE);
    }

    public AuthSessionProvider(Path sessionBaseDir, Duration maxAge) {
        this.sessionBaseDir = sessionBaseDir != null ? sessionBaseDir : resolveDefaultSessionDir();
        this.maxAge = (maxAge != null && !maxAge.isNegative() && !maxAge.isZero()) ? maxAge : DEFAULT_MAX_AGE;
    }

    /**
     * Reads pre-minted storage state for targetArea, returning a temporary file with 0600 permissions.
     * Returns Optional.empty() if session does not exist, is invalid, or has expired (§6.8).
     */
    public Optional<Path> read(String targetArea) {
        if (targetArea == null || targetArea.isBlank()) {
            log.warn("Cannot read auth session for blank targetArea");
            return Optional.empty();
        }

        Optional<Path> sourceFileOpt = findSessionFile(targetArea);
        if (sourceFileOpt.isEmpty()) {
            log.info("No session file found for targetArea '{}' in {}", targetArea, sessionBaseDir);
            return Optional.empty();
        }

        Path sourceFile = sourceFileOpt.get();

        // 1. Freshness check
        try {
            FileTime lastModified = Files.getLastModifiedTime(sourceFile);
            Instant expiry = lastModified.toInstant().plus(maxAge);
            if (Instant.now().isAfter(expiry)) {
                log.warn("Session file {} for targetArea '{}' has expired (last modified: {}, maxAge: {})",
                        sourceFile, targetArea, lastModified, maxAge);
                return Optional.empty();
            }
        } catch (IOException e) {
            log.warn("Failed to check last modified time on {}: {}", sourceFile, e.getMessage());
            return Optional.empty();
        }

        // 2. Copy to short-lived temp file with 0600 permissions
        try {
            String sanitized = targetArea.replaceAll("[^a-zA-Z0-9_-]", "_");
            Path tempFile = Files.createTempFile("pw-session-" + sanitized + "-", ".json");
            Files.copy(sourceFile, tempFile, StandardCopyOption.REPLACE_EXISTING);

            apply0600Permissions(tempFile);
            log.info("Successfully loaded session for targetArea '{}' into {}", targetArea, tempFile);
            return Optional.of(tempFile);
        } catch (IOException e) {
            log.error("Failed to create temporary session file for targetArea '{}': {}", targetArea, e.getMessage(), e);
            return Optional.empty();
        }
    }

    private Optional<Path> findSessionFile(String targetArea) {
        if (sessionBaseDir == null || !Files.isDirectory(sessionBaseDir)) {
            return Optional.empty();
        }

        List<Path> candidates = List.of(
                sessionBaseDir.resolve(targetArea + ".json"),
                sessionBaseDir.resolve(targetArea.toLowerCase() + ".json"),
                sessionBaseDir.resolve(targetArea).resolve("storageState.json"),
                sessionBaseDir.resolve(targetArea.toLowerCase()).resolve("storageState.json"),
                sessionBaseDir.resolve("storageState.json")
        );

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Applies strict 0600 (owner read+write only) permissions to the given file.
     */
    public static void apply0600Permissions(Path file) {
        Objects.requireNonNull(file, "file must not be null");
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            );
            Files.setPosixFilePermissions(file, permissions);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Windows / Non-POSIX filesystem fallback
            File f = file.toFile();
            f.setReadable(false, false);
            f.setReadable(true, true);
            f.setWritable(false, false);
            f.setWritable(true, true);
            f.setExecutable(false, false);
        }
    }

    private static Path resolveDefaultSessionDir() {
        String envDir = System.getenv("TESTGEN_SESSION_DIR");
        if (envDir != null && !envDir.isBlank()) {
            return Paths.get(envDir);
        }
        String propDir = System.getProperty("testgen.session.dir");
        if (propDir != null && !propDir.isBlank()) {
            return Paths.get(propDir);
        }
        return Paths.get("sessions");
    }

    private static Path resolveSessionDirFromProps(PipelineProperties.SessionProperties props) {
        if (props != null && props.vaultPath() != null && !props.vaultPath().isBlank()) {
            return Paths.get(props.vaultPath());
        }
        return resolveDefaultSessionDir();
    }
}
