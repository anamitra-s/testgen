package com.company.testgen.phases;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Secure file deletion utility (§6.8, §9.4).
 * Overwrites token material with zeroes before deletion so session credentials never outlive the phase.
 */
public final class SecureDelete {

    private static final Logger log = LoggerFactory.getLogger(SecureDelete.class);

    private SecureDelete() {}

    /**
     * Overwrites file contents with zeros and deletes the file from disk.
     * Safe to call from finally blocks: catches all exceptions and logs without rethrowing.
     */
    public static void of(Path path) {
        if (path == null) {
            return;
        }
        try {
            if (Files.exists(path) && Files.isRegularFile(path)) {
                long size = Files.size(path);
                if (size > 0) {
                    byte[] zeros = new byte[(int) Math.min(size, 65536)];
                    Arrays.fill(zeros, (byte) 0);
                    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                        long remaining = size;
                        while (remaining > 0) {
                            int toWrite = (int) Math.min(remaining, zeros.length);
                            channel.write(ByteBuffer.wrap(zeros, 0, toWrite));
                            remaining -= toWrite;
                        }
                        channel.force(true);
                    }
                }
            }
            Files.deleteIfExists(path);
            log.debug("Securely deleted session file at {}", path);
        } catch (IOException e) {
            log.warn("Failed to securely overwrite file {}: {}. Attempting plain deletion.", path, e.getMessage());
            try {
                Files.deleteIfExists(path);
            } catch (Exception ex) {
                log.warn("Failed to delete file {}: {}", path, ex.getMessage());
            }
        }
    }
}
