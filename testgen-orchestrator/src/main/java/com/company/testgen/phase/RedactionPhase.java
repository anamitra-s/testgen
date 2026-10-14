package com.company.testgen.phase;

import com.company.testgen.model.PageSnapshot;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.Redacted;
import com.company.testgen.model.RedactionReport;
import com.company.testgen.redaction.DomRedactor;
import com.company.testgen.redaction.RedactionFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 2: Mandatory fail-closed DOM snapshot and data redaction (§6.9, §6.13, FR-06).
 * Strips non-allowlisted attributes, scrubs PII with typed tokens, normalizes URLs, and compiles an audit report.
 */
public class RedactionPhase {

    private static final Logger log = LoggerFactory.getLogger(RedactionPhase.class);

    private final DomRedactor domRedactor;

    public RedactionPhase() {
        this(new DomRedactor());
    }

    public RedactionPhase(DomRedactor domRedactor) {
        this.domRedactor = Objects.requireNonNull(domRedactor, "domRedactor cannot be null");
    }

    /**
     * Executes fail-closed redaction on the parsed test plan.
     *
     * @param plan the parsed test plan
     * @return redacted container with safe plan and comprehensive audit report
     * @throws RedactionFailedException if any error occurs during redaction
     */
    public Redacted<ParsedTestPlan> execute(ParsedTestPlan plan) {
        if (plan == null) {
            throw new RedactionFailedException("Cannot redact null ParsedTestPlan");
        }

        try {
            log.info("Executing RedactionPhase on test plan with {} snapshots", plan.snapshots().size());

            List<PageSnapshot> safeSnapshots = new ArrayList<>();
            Map<String, Integer> rulesApplied = new LinkedHashMap<>();
            Map<String, Integer> attributesDropped = new LinkedHashMap<>();
            int urlsNormalized = 0;
            int textsTruncated = 0;

            for (PageSnapshot rawSnapshot : plan.snapshots()) {
                Redacted<PageSnapshot> redactedSnap = domRedactor.redact(rawSnapshot);
                safeSnapshots.add(redactedSnap.value());

                RedactionReport rep = redactedSnap.report();
                if (rep != null) {
                    if (rep.rulesApplied() != null) {
                        rep.rulesApplied().forEach((k, v) -> rulesApplied.merge(k, v, Integer::sum));
                    }
                    if (rep.attributesDropped() != null) {
                        rep.attributesDropped().forEach((k, v) -> attributesDropped.merge(k, v, Integer::sum));
                    }
                    urlsNormalized += rep.urlsNormalized();
                    textsTruncated += rep.textsTruncated();
                }
            }

            // Derive aggregate payload SHA-256 over all safe snapshots
            String payloadSha256 = computeCombinedPayloadSha(safeSnapshots);

            RedactionReport combinedReport = new RedactionReport(
                    rulesApplied,
                    attributesDropped,
                    urlsNormalized,
                    textsTruncated,
                    payloadSha256,
                    "redaction-v3"
            );

            ParsedTestPlan safePlan = new ParsedTestPlan(
                    plan.targetArea(),
                    plan.testDescription(),
                    plan.actions(),
                    safeSnapshots,
                    plan.containsLoginFlow(),
                    plan.mutatesData()
            );

            log.info("Redaction completed successfully: rulesApplied={}, attributesDropped={}, payloadSha={}",
                    rulesApplied.values().stream().mapToInt(Integer::intValue).sum(),
                    attributesDropped.values().stream().mapToInt(Integer::intValue).sum(),
                    payloadSha256);

            return new Redacted<>(safePlan, combinedReport);

        } catch (RedactionFailedException rfe) {
            log.error("Fail-closed redaction failed: {}", rfe.getMessage());
            throw rfe;
        } catch (Exception e) {
            log.error("Unexpected error during redaction phase - aborting fail-closed: {}", e.getMessage(), e);
            throw new RedactionFailedException("Redaction phase failed: " + e.getMessage(), e);
        }
    }

    private String computeCombinedPayloadSha(List<PageSnapshot> snapshots) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (PageSnapshot snap : snapshots) {
                if (snap.prunedHtml() != null) {
                    digest.update(snap.prunedHtml().getBytes(StandardCharsets.UTF_8));
                }
                if (snap.normalizedUrl() != null) {
                    digest.update(snap.normalizedUrl().getBytes(StandardCharsets.UTF_8));
                }
                digest.update((byte) 0);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RedactionFailedException("SHA-256 algorithm not available", e);
        }
    }
}
