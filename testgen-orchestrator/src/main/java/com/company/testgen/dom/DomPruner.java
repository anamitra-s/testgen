package com.company.testgen.dom;

import com.company.testgen.config.PipelineProperties;
import com.company.testgen.model.DomElementSnapshot;
import com.company.testgen.model.PageSnapshot;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * DOM Pruner bounding snapshot HTML to token and node limits (§6.3, FR-07).
 * Drops farthest ancestors first, then non-candidate leaves, and sets truncated=true when caps bind.
 */
public class DomPruner {

    private static final Logger log = LoggerFactory.getLogger(DomPruner.class);

    public static final int DEFAULT_MAX_NODES = 2000;
    public static final int DEFAULT_MAX_SNAPSHOT_TOKENS = 20_000;
    private static final double CHARS_PER_TOKEN = 3.6;

    private final int maxSnapshotTokens;
    private final int maxNodes;

    public DomPruner() {
        this(DEFAULT_MAX_SNAPSHOT_TOKENS, DEFAULT_MAX_NODES);
    }

    public DomPruner(int maxSnapshotTokens, int maxNodes) {
        this.maxSnapshotTokens = maxSnapshotTokens > 0 ? maxSnapshotTokens : DEFAULT_MAX_SNAPSHOT_TOKENS;
        this.maxNodes = maxNodes > 0 ? maxNodes : DEFAULT_MAX_NODES;
    }

    public DomPruner(PipelineProperties.DomProperties props) {
        this(
                props != null && props.maxSnapshotTokens() > 0 ? props.maxSnapshotTokens() : DEFAULT_MAX_SNAPSHOT_TOKENS,
                props != null && props.maxNodes() > 0 ? props.maxNodes() : DEFAULT_MAX_NODES
        );
    }

    public record PruneResult(String prunedHtml, int nodeCount, boolean truncated) {}

    /**
     * Prunes a PageSnapshot and returns a new PageSnapshot with bounded prunedHtml and updated truncated flag.
     */
    public PageSnapshot prune(PageSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        if (!snapshot.hasHtml()) {
            return snapshot;
        }

        PruneResult result = pruneHtml(snapshot.prunedHtml(), snapshot.interactedElements());
        return snapshot.withPrunedHtml(result.prunedHtml(), result.truncated() || snapshot.truncated());
    }

    /**
     * Prunes HTML string bounding node count and estimated tokens.
     */
    public PruneResult pruneHtml(String html, List<DomElementSnapshot> interactedElements) {
        if (html == null || html.isBlank()) {
            return new PruneResult(html, 0, false);
        }

        Document doc = Jsoup.parse(html);
        doc.outputSettings().prettyPrint(false);

        // Strip scripts, styles, noscript, template, svg internals, comments
        cleanIrrelevantNodes(doc);

        int initialNodes = doc.getAllElements().size();
        int initialTokens = estimateTokens(doc);

        if (initialNodes <= maxNodes && initialTokens <= maxSnapshotTokens) {
            return new PruneResult(render(doc), initialNodes, false);
        }

        log.debug("DOM snapshot exceeds limits (nodes={}, tokens={}, capNodes={}, capTokens={}); pruning",
                initialNodes, initialTokens, maxNodes, maxSnapshotTokens);

        boolean truncated = true;

        // Collect essential candidate elements (interacted elements + candidate locator targets)
        Set<Element> candidateElements = identifyCandidates(doc, interactedElements);
        Set<Element> ancestorChain = new HashSet<>();
        for (Element candidate : candidateElements) {
            Element p = candidate.parent();
            while (p != null) {
                ancestorChain.add(p);
                p = p.parent();
            }
        }

        // 1. Drop farthest ancestors' unneeded branches (non-candidate subtrees high in the DOM hierarchy)
        dropFarthestNonCandidateAncestors(doc, candidateElements, ancestorChain);

        // Check if now within bounds
        if (isWithinBounds(doc)) {
            return new PruneResult(render(doc), doc.getAllElements().size(), truncated);
        }

        // 2. Drop non-candidate leaves iteratively
        dropNonCandidateLeaves(doc, candidateElements, ancestorChain);

        // Check if now within bounds
        if (isWithinBounds(doc)) {
            return new PruneResult(render(doc), doc.getAllElements().size(), truncated);
        }

        // 3. Unwrap distant single-child ancestor wrappers
        unwrapDistantAncestors(doc, candidateElements);

        if (isWithinBounds(doc)) {
            return new PruneResult(render(doc), doc.getAllElements().size(), truncated);
        }

        // 4. Force bound by distance from interacted targets if still exceeding
        forceBoundToLimits(doc, candidateElements);

        return new PruneResult(render(doc), doc.getAllElements().size(), truncated);
    }

    private void cleanIrrelevantNodes(Document doc) {
        doc.select("script, style, noscript, template, iframe").remove();

        // Clear internals of svg tags, leaving only svg tag
        for (Element svg : doc.select("svg")) {
            svg.empty();
        }

        // Remove comments
        doc.filter(new NodeFilter() {
            @Override
            public FilterResult head(Node node, int depth) {
                return (node instanceof Comment) ? FilterResult.REMOVE : FilterResult.CONTINUE;
            }

            @Override
            public FilterResult tail(Node node, int depth) {
                return FilterResult.CONTINUE;
            }
        });
    }

    private Set<Element> identifyCandidates(Document doc, List<DomElementSnapshot> interacted) {
        Set<Element> candidates = new HashSet<>();

        // Interacted elements matching tag/test-id/accessible name
        if (interacted != null) {
            for (DomElementSnapshot elem : interacted) {
                if (elem == null) continue;
                if (elem.attributes() != null && elem.attributes().containsKey("data-testid")) {
                    candidates.addAll(doc.select("[data-testid=\"" + elem.attributes().get("data-testid") + "\"]"));
                }
                if (elem.attributes() != null && elem.attributes().containsKey("id")) {
                    candidates.addAll(doc.select("#" + elem.attributes().get("id")));
                }
                if (elem.role() != null && !elem.role().isBlank()) {
                    candidates.addAll(doc.select("[role=\"" + elem.role() + "\"]"));
                }
            }
        }

        // Candidate locator targets (bearing data-testid, aria-label, role, name, placeholder)
        candidates.addAll(doc.select("[data-testid], [aria-label], [role], [name], [placeholder]"));
        return candidates;
    }

    private void dropFarthestNonCandidateAncestors(Document doc, Set<Element> candidates, Set<Element> ancestorChain) {
        Element body = doc.body();
        if (body == null) return;

        // High-level children under body (e.g., <header>, <footer>, <nav>, outer layout containers)
        List<Element> topLevelBranches = new ArrayList<>(body.children());
        for (Element top : topLevelBranches) {
            if (!ancestorChain.contains(top) && !candidates.contains(top)) {
                top.remove();
                if (isWithinBounds(doc)) {
                    return;
                }
            }
        }

        // For ancestors in the chain, drop siblings that are not in the ancestor chain and contain no candidates
        List<Element> sortedAncestors = new ArrayList<>(ancestorChain);
        // Sort farthest from leaf (closest to root/lowest depth) first
        sortedAncestors.sort(Comparator.comparingInt(this::elementDepth));

        for (Element ancestor : sortedAncestors) {
            List<Element> siblings = new ArrayList<>(ancestor.children());
            for (Element sibling : siblings) {
                if (!ancestorChain.contains(sibling) && !candidates.contains(sibling) && !containsAny(sibling, candidates)) {
                    sibling.remove();
                    if (isWithinBounds(doc)) {
                        return;
                    }
                }
            }
        }
    }

    private void dropNonCandidateLeaves(Document doc, Set<Element> candidates, Set<Element> ancestorChain) {
        boolean removedAny = true;
        while (removedAny && !isWithinBounds(doc)) {
            removedAny = false;
            List<Element> leaves = doc.getAllElements().stream()
                    .filter(el -> el.children().isEmpty())
                    .filter(el -> !el.tagName().equalsIgnoreCase("body") && !el.tagName().equalsIgnoreCase("html"))
                    .filter(el -> !candidates.contains(el))
                    .toList();

            for (Element leaf : leaves) {
                if (!candidates.contains(leaf) && !ancestorChain.contains(leaf)) {
                    leaf.remove();
                    removedAny = true;
                    if (isWithinBounds(doc)) {
                        return;
                    }
                }
            }
        }
    }

    private void unwrapDistantAncestors(Document doc, Set<Element> candidates) {
        Element body = doc.body();
        if (body == null) return;

        // If body has only 1 child container that is not a candidate itself, unwrap it
        while (body.children().size() == 1 && !isWithinBounds(doc)) {
            Element singleChild = body.children().first();
            if (singleChild != null && !candidates.contains(singleChild) && !singleChild.children().isEmpty()) {
                singleChild.unwrap();
            } else {
                break;
            }
        }
    }

    private void forceBoundToLimits(Document doc, Set<Element> candidates) {
        // Sort all non-candidate elements by depth descending (deepest leaves first)
        List<Element> all = new ArrayList<>(doc.getAllElements());
        all.sort(Comparator.comparingInt(this::elementDepth).reversed());

        for (Element el : all) {
            if (isWithinBounds(doc)) {
                return;
            }
            if (!candidates.contains(el) && !el.tagName().equalsIgnoreCase("body") && !el.tagName().equalsIgnoreCase("html")) {
                el.remove();
            }
        }

        // If STILL over token budget, truncate long text contents
        for (Element el : doc.getAllElements()) {
            if (isWithinBounds(doc)) {
                return;
            }
            if (el.hasText() && el.ownText().length() > 50) {
                el.text(el.ownText().substring(0, 50));
            }
        }
    }

    private boolean isWithinBounds(Document doc) {
        return doc.getAllElements().size() <= maxNodes && estimateTokens(doc) <= maxSnapshotTokens;
    }

    private int estimateTokens(Document doc) {
        String html = render(doc);
        return (int) Math.ceil(html.length() / CHARS_PER_TOKEN);
    }

    private int elementDepth(Element el) {
        int depth = 0;
        Element cur = el;
        while (cur != null) {
            depth++;
            cur = cur.parent();
        }
        return depth;
    }

    private boolean containsAny(Element parent, Set<Element> targets) {
        for (Element child : parent.getAllElements()) {
            if (targets.contains(child)) {
                return true;
            }
        }
        return false;
    }

    private String render(Document doc) {
        return doc.body() != null ? doc.body().html() : doc.html();
    }

    public int maxSnapshotTokens() {
        return maxSnapshotTokens;
    }

    public int maxNodes() {
        return maxNodes;
    }
}
