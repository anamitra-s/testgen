package com.company.testgen.redaction;

import com.company.testgen.idempotency.IdempotencyKeyDeriver;
import com.company.testgen.model.DomElementSnapshot;
import com.company.testgen.model.PageSnapshot;
import com.company.testgen.model.Redacted;
import com.company.testgen.model.RedactionReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Redaction engine for DOM snapshots (§6.9).
 * <p>
 * <b>The only component in the system that fails closed.</b>
 * Aborts cleanly before any external call if an unexpected error occurs.
 */
public class DomRedactor {

    private final PiiScrubber pii;
    private final UrlNormalizer urls;
    private final ObjectMapper mapper;

    public DomRedactor() {
        this(new PiiScrubber(), new UrlNormalizer());
    }

    public DomRedactor(PiiScrubber pii, UrlNormalizer urls) {
        this.pii = Objects.requireNonNull(pii, "pii cannot be null");
        this.urls = Objects.requireNonNull(urls, "urls cannot be null");
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Redacts a raw {@link PageSnapshot}, stripping disallowed attributes, comments,
     * script/style elements, normalizing URLs, and scrubbing PII from text and attributes.
     *
     * @param raw the captured page snapshot
     * @return container holding the safe snapshot and audit report
     * @throws RedactionFailedException if any error occurs (fail-closed)
     */
    public Redacted<PageSnapshot> redact(PageSnapshot raw) {
        if (raw == null) {
            throw new RedactionFailedException("Cannot redact null PageSnapshot");
        }

        RedactionReport.Builder report = RedactionReport.builder();
        try {
            String html = raw.hasHtml() ? redactHtml(raw.prunedHtml(), report) : null;

            List<DomElementSnapshot> els = raw.interactedElements() != null
                    ? raw.interactedElements().stream()
                        .map(e -> new DomElementSnapshot(
                                e.elementRefId(),
                                e.tagName(),
                                filterAttrs(e.attributes(), report),
                                scrubText(e.textContent(), report),
                                e.role(),
                                scrubText(e.accessibleName(), report),
                                e.fingerprint()))              // fingerprint is structural — no PII
                        .toList()
                    : List.of();

            String safeUrl = urls.normalize(raw.normalizedUrl(), report);

            PageSnapshot safe = new PageSnapshot(
                    raw.stateId(),
                    safeUrl,
                    html,
                    els,
                    raw.capturedAt(),
                    raw.truncated());

            String jsonPayload = mapper.writeValueAsString(safe);
            String hash = IdempotencyKeyDeriver.sha256Hex(jsonPayload);
            report.payloadSha256("sha256:" + hash);

            return new Redacted<>(safe, report.build());

        } catch (Exception e) {
            // FAIL CLOSED. A validator that fails open costs a lower-confidence MR.
            // A redactor that fails open costs a data-exposure incident.
            if (e instanceof RedactionFailedException rfe) {
                throw rfe;
            }
            throw new RedactionFailedException(
                    "Redaction failed; job aborted before any external call", e);
        }
    }

    private String redactHtml(String html, RedactionReport.Builder r) {
        if (html == null || html.isBlank()) {
            return html;
        }

        Document doc = Jsoup.parse(html);
        doc.outputSettings().prettyPrint(false);

        // 1. Remove active/dangerous or irrelevant tags
        doc.select("script, style, noscript, svg, iframe, template").remove();

        // 2. Filter out comments
        doc.filter(new NodeFilter() {
            @Override
            public FilterResult head(Node node, int depth) {
                if (node instanceof Comment) {
                    return FilterResult.REMOVE;
                }
                return FilterResult.CONTINUE;
            }

            @Override
            public FilterResult tail(Node node, int depth) {
                return FilterResult.CONTINUE;
            }
        });

        // 3. Filter attributes and scrub text nodes for all elements
        for (Element el : doc.getAllElements()) {
            List<String> toRemove = new ArrayList<>();
            for (Attribute attr : el.attributes()) {
                String key = attr.getKey();
                if (!RedactionRules.isAllowed(key)) {
                    toRemove.add(key);
                } else if (!"href".equalsIgnoreCase(key)) {
                    String origVal = attr.getValue();
                    if (origVal != null && !origVal.isEmpty()) {
                        String scrubbed = scrubText(origVal, r);
                        if (!scrubbed.equals(origVal)) {
                            attr.setValue(scrubbed);
                        }
                    }
                }
            }
            for (String k : toRemove) {
                el.removeAttr(k);
                r.dropped(k);
            }

            if (el.hasAttr("href")) {
                el.attr("href", urls.normalize(el.attr("href"), r));
            }

            for (TextNode tn : el.textNodes()) {
                String orig = tn.getWholeText();
                if (orig != null && !orig.isEmpty()) {
                    String scrubbed = scrubText(orig, r);
                    if (!scrubbed.equals(orig)) {
                        tn.text(scrubbed);
                    }
                }
            }
        }

        return doc.body().html();
    }

    private Map<String, String> filterAttrs(Map<String, String> attributes, RedactionReport.Builder r) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String k = entry.getKey();
            if (RedactionRules.isAllowed(k)) {
                String val = entry.getValue();
                if (val != null) {
                    if ("href".equalsIgnoreCase(k)) {
                        val = urls.normalize(val, r);
                    } else {
                        val = scrubText(val, r);
                    }
                }
                safe.put(k, val);
            } else {
                r.dropped(k);
            }
        }
        return Collections.unmodifiableMap(safe);
    }

    private String scrubText(String text, RedactionReport.Builder r) {
        if (text == null) {
            return null;
        }
        return pii.scrub(text, r);
    }
}
