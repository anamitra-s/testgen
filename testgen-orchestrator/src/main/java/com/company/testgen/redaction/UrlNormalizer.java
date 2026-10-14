package com.company.testgen.redaction;

import com.company.testgen.model.RedactionReport;

import java.util.regex.Pattern;

/**
 * Normalizes URLs by stripping queries/fragments and replacing numeric/UUID segments with {@code {id}} (§6.9).
 */
public class UrlNormalizer {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern HEX32_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^\\d+$");
    private static final Pattern SHORT_UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{4,}-[0-9a-fA-F]{4,}.*$");

    /**
     * Normalizes a URL and records the drop/normalization in the audit report if modified.
     *
     * @param url raw URL or relative path
     * @param report audit report builder
     * @return normalized URL string
     */
    public String normalize(String url, RedactionReport.Builder report) {
        if (url == null) {
            return null;
        }
        if (url.isBlank()) {
            return url;
        }

        // 1. Strip query string (?) and fragment (#)
        String stripped = url;
        int qIdx = stripped.indexOf('?');
        int fIdx = stripped.indexOf('#');
        int cutIdx = -1;
        if (qIdx != -1 && fIdx != -1) {
            cutIdx = Math.min(qIdx, fIdx);
        } else if (qIdx != -1) {
            cutIdx = qIdx;
        } else if (fIdx != -1) {
            cutIdx = fIdx;
        }

        if (cutIdx != -1) {
            stripped = stripped.substring(0, cutIdx);
        }

        // 2. Separate scheme/host from path if absolute URL
        String prefix = "";
        String path = stripped;

        int schemeIdx = stripped.indexOf("://");
        if (schemeIdx != -1) {
            int pathStart = stripped.indexOf('/', schemeIdx + 3);
            if (pathStart != -1) {
                prefix = stripped.substring(0, pathStart);
                path = stripped.substring(pathStart);
            } else {
                prefix = stripped;
                path = "";
            }
        }

        // 3. Process path segments
        String normalizedPath = path;
        if (!path.isEmpty()) {
            String[] segments = path.split("/", -1);
            boolean modified = false;
            for (int i = 0; i < segments.length; i++) {
                String seg = segments[i];
                if (isIdSegment(seg)) {
                    segments[i] = "{id}";
                    modified = true;
                }
            }
            if (modified) {
                normalizedPath = String.join("/", segments);
            }
        }

        String result = prefix + normalizedPath;
        if (!result.equals(url) && report != null) {
            report.urlNormalized();
        }
        return result;
    }

    public static boolean isIdSegment(String segment) {
        if (segment == null || segment.isEmpty() || "{id}".equals(segment)) {
            return false;
        }
        return NUMERIC_PATTERN.matcher(segment).matches()
                || UUID_PATTERN.matcher(segment).matches()
                || HEX32_PATTERN.matcher(segment).matches()
                || SHORT_UUID_PATTERN.matcher(segment).matches();
    }
}
