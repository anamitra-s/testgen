package com.company.testgen.idempotency;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Derives content-based idempotency keys from recording artifacts (§7.2).
 * <p>
 * Key generation format:
 * <pre>
 *   "v2:" + sha256Hex(KEY_VERSION + "\0" + canonicalElements + "\0" + canonicalScript + "\0" + targetArea + "\0" + testDescription)
 * </pre>
 */
public final class IdempotencyKeyDeriver {

    public static final String KEY_VERSION = "v2";

    /**
     * Derives the content-addressed idempotency key from a recording directory.
     *
     * @param recordingFolder directory containing {@code recorded-elements.json} and {@code recorded-test.js}
     * @param targetArea target area name (e.g. "dashboard")
     * @param testDescription human-readable test description
     * @return deterministic idempotency key string starting with "v2:"
     */
    public String derive(Path recordingFolder, String targetArea, String testDescription) throws IOException {
        Path elementsPath = recordingFolder.resolve("recorded-elements.json");
        Path scriptPath = recordingFolder.resolve("recorded-test.js");

        if (!Files.exists(elementsPath)) {
            throw new IllegalArgumentException("recorded-elements.json not found in " + recordingFolder);
        }
        if (!Files.exists(scriptPath)) {
            throw new IllegalArgumentException("recorded-test.js not found in " + recordingFolder);
        }

        // Canonicalization per §7.2
        String canonicalElements = JsonCanonicalizer.canonicalize(
                Files.readString(elementsPath, StandardCharsets.UTF_8));
        String canonicalScript = Files.readString(scriptPath, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .strip();

        String payload = String.join("\u0000",
                KEY_VERSION,
                canonicalElements,
                canonicalScript,
                targetArea.strip().toLowerCase(Locale.ROOT),
                testDescription.strip());

        return KEY_VERSION + ":" + sha256Hex(payload);
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
