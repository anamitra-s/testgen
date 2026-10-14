package com.company.testgen.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * RFC 8785 JSON Canonicalization Scheme (JCS) implementation (§7.2).
 * <p>
 * Ensures deterministic serialization across platforms, programming languages,
 * and JSON serializers:
 * <ul>
 *   <li>Lexicographical key sorting (UTF-16 code units)</li>
 *   <li>No insignificant whitespace</li>
 *   <li>Normalized numeric and string representations</li>
 *   <li>Excludes {@code capturedAt} timestamp at the root of recording JSON per §7.2</li>
 * </ul>
 */
public final class JsonCanonicalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonCanonicalizer() {}

    /**
     * Canonicalizes recording JSON, deliberately excluding {@code capturedAt} per §7.2.
     */
    public static String canonicalize(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        if (root.isObject() && ((ObjectNode) root).has("capturedAt")) {
            ((ObjectNode) root).remove("capturedAt");
        }
        return canonicalizeNode(root);
    }

    /**
     * Canonicalizes arbitrary JSON preserving all fields per RFC 8785.
     */
    public static String canonicalizeRaw(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        return canonicalizeNode(root);
    }

    public static String canonicalizeNode(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        appendNode(node, sb);
        return sb.toString();
    }

    private static void appendNode(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull()) {
            sb.append("null");
        } else if (node.isBoolean()) {
            sb.append(node.asBoolean() ? "true" : "false");
        } else if (node.isTextual()) {
            escapeString(node.asText(), sb);
        } else if (node.isIntegralNumber()) {
            sb.append(node.bigIntegerValue().toString());
        } else if (node.isFloatingPointNumber()) {
            double d = node.asDouble();
            if (d == 0.0) {
                sb.append("0");
            } else if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e21) {
                sb.append(String.format(Locale.ROOT, "%.0f", d));
            } else {
                sb.append(node.asText());
            }
        } else if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendNode(node.get(i), sb);
            }
            sb.append(']');
        } else if (node.isObject()) {
            sb.append('{');
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);

            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                String key = keys.get(i);
                escapeString(key, sb);
                sb.append(':');
                appendNode(node.get(key), sb);
            }
            sb.append('}');
        } else {
            sb.append(node.asText());
        }
    }

    private static void escapeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
