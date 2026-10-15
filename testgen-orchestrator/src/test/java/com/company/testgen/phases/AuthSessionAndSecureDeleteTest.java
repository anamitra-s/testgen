package com.company.testgen.phases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthSessionAndSecureDeleteTest {

    @Test
    @DisplayName("AuthSessionProvider returns empty when session file does not exist")
    void testSessionNotFound(@TempDir Path tempDir) {
        AuthSessionProvider provider = new AuthSessionProvider(tempDir, Duration.ofHours(4));
        Optional<Path> session = provider.read("nonexistent");
        assertThat(session).isEmpty();
    }

    @Test
    @DisplayName("AuthSessionProvider returns empty when session file is expired")
    void testSessionExpired(@TempDir Path tempDir) throws IOException {
        Path sessionFile = tempDir.resolve("billing.json");
        Files.writeString(sessionFile, "{\"cookies\": [\"token=secret\"]}");

        // Set last modified time to 5 hours ago
        FileTime oldTime = FileTime.from(Instant.now().minus(Duration.ofHours(5)));
        Files.setLastModifiedTime(sessionFile, oldTime);

        AuthSessionProvider provider = new AuthSessionProvider(tempDir, Duration.ofHours(4));
        Optional<Path> session = provider.read("billing");
        assertThat(session).isEmpty();
    }

    @Test
    @DisplayName("AuthSessionProvider copies fresh session to temp file with 0600 permissions")
    void testSessionFreshWith0600Permissions(@TempDir Path tempDir) throws IOException {
        Path sessionFile = tempDir.resolve("billing.json");
        String content = "{\"cookies\": [\"token=fresh-token\"]}";
        Files.writeString(sessionFile, content);

        AuthSessionProvider provider = new AuthSessionProvider(tempDir, Duration.ofHours(4));
        Optional<Path> sessionOpt = provider.read("billing");

        assertThat(sessionOpt).isPresent();
        Path tempSession = sessionOpt.get();

        try {
            assertThat(tempSession).exists();
            assertThat(Files.readString(tempSession)).isEqualTo(content);

            // Verify file permissions (POSIX if supported)
            PosixFileAttributeView posixView = Files.getFileAttributeView(tempSession, PosixFileAttributeView.class);
            if (posixView != null) {
                Set<PosixFilePermission> perms = posixView.readAttributes().permissions();
                assertThat(perms).containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE
                );
            } else {
                // Non-POSIX (Windows): verify readable/writable
                assertThat(tempSession.toFile().canRead()).isTrue();
                assertThat(tempSession.toFile().canWrite()).isTrue();
            }
        } finally {
            SecureDelete.of(tempSession);
        }
    }

    @Test
    @DisplayName("SecureDelete zeroes file contents and deletes it from disk")
    void testSecureDeleteZeroesAndDeletes(@TempDir Path tempDir) throws IOException {
        Path sensitiveFile = tempDir.resolve("secret.json");
        Files.writeString(sensitiveFile, "SENSITIVE_SESSION_TOKEN_12345");

        assertThat(sensitiveFile).exists();
        SecureDelete.of(sensitiveFile);

        assertThat(sensitiveFile).doesNotExist();

        // Calling SecureDelete again on non-existent or null path does not throw
        SecureDelete.of(sensitiveFile);
        SecureDelete.of(null);
    }
}
