package com.company.testgen.dom;

import com.company.testgen.model.DomElementSnapshot;
import com.company.testgen.model.PageSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DomPrunerTest {

    @Test
    @DisplayName("DomPruner leaves small HTML below limits intact with truncated=false")
    void testPruneUnderLimits() {
        DomPruner pruner = new DomPruner(1000, 50);
        String html = "<html><body><div id=\"root\"><button data-testid=\"submit-btn\">Submit</button></div></body></html>";

        PageSnapshot snapshot = new PageSnapshot("s1", "https://app.example.com", html, List.of(), Instant.now(), false);
        PageSnapshot pruned = pruner.prune(snapshot);

        assertThat(pruned.truncated()).isFalse();
        assertThat(pruned.prunedHtml()).contains("data-testid=\"submit-btn\"");
        assertThat(pruned.prunedHtml()).contains("Submit");
    }

    @Test
    @DisplayName("DomPruner enforces maxNodes limit, drops non-candidate elements, and sets truncated=true")
    void testPruneExceedingMaxNodes() {
        // Create an HTML document with 80 leaf elements, but set maxNodes to 30
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body><header><nav>");
        for (int i = 0; i < 25; i++) {
            sb.append("<span>Nav item ").append(i).append("</span>");
        }
        sb.append("</nav></header>");
        sb.append("<main id=\"root\"><div class=\"container\">");
        sb.append("<button data-testid=\"target-btn\">Important Action</button>");
        for (int i = 0; i < 50; i++) {
            sb.append("<p>Paragraph ").append(i).append("</p>");
        }
        sb.append("</div></main></body></html>");

        DomElementSnapshot targetElem = new DomElementSnapshot(
                "e1", "button", Map.of("data-testid", "target-btn"), "Important Action", "button", "Important Action", null
        );

        PageSnapshot snapshot = new PageSnapshot(
                "s1", "https://app.example.com", sb.toString(), List.of(targetElem), Instant.now(), false
        );

        // Max 30 nodes
        DomPruner pruner = new DomPruner(10_000, 30);
        PageSnapshot pruned = pruner.prune(snapshot);

        assertThat(pruned.truncated()).isTrue();
        // Candidate must be preserved
        assertThat(pruned.prunedHtml()).contains("data-testid=\"target-btn\"");
        assertThat(pruned.prunedHtml()).contains("Important Action");

        // High-level irrelevant header navigation should have been dropped first
        assertThat(pruned.prunedHtml()).doesNotContain("Nav item 24");
    }

    @Test
    @DisplayName("DomPruner enforces maxSnapshotTokens limit and sets truncated=true")
    void testPruneExceedingTokens() {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body><div id=\"app\">");
        sb.append("<button data-testid=\"buy-now\">Buy Now</button>");
        for (int i = 0; i < 50; i++) {
            sb.append("<div class=\"filler\">Long decorative text filler content line ").append(i).append("</div>");
        }
        sb.append("</div></body></html>");

        PageSnapshot snapshot = new PageSnapshot(
                "s1", "https://app.example.com", sb.toString(), List.of(), Instant.now(), false
        );

        // 100 tokens max (approx 360 chars)
        DomPruner pruner = new DomPruner(100, 2000);
        PageSnapshot pruned = pruner.prune(snapshot);

        assertThat(pruned.truncated()).isTrue();
        assertThat(pruned.prunedHtml()).contains("data-testid=\"buy-now\"");
        int estimatedTokens = (int) Math.ceil(pruned.prunedHtml().length() / 3.6);
        assertThat(estimatedTokens).isLessThanOrEqualTo(100);
    }
}
