package com.company.testgen.phase;

import com.company.testgen.dom.DomPruner;
import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.PageSnapshot;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.RecordedAction;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.validation.ExistingPageObjectAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblyPhaseTest {

    @Test
    @DisplayName("ContextAssemblyPhase prunes DOM snapshots and analyzes existing Page Objects")
    void testContextAssemblyIntegration(@TempDir Path tempDir) throws IOException {
        Path pagesDir = tempDir.resolve("pages/billing");
        Files.createDirectories(pagesDir);
        Files.writeString(pagesDir.resolve("InvoicePage.java"), """
                package com.company.testgen.pages.billing;
                public class InvoicePage {
                    public void downloadPdf() {}
                }
                """);

        DomPruner pruner = new DomPruner(10_000, 50);
        ExistingPageObjectAnalyzer analyzer = new ExistingPageObjectAnalyzer();
        ContextAssemblyPhase phase = new ContextAssemblyPhase(pruner, analyzer, tempDir);

        PageSnapshot rawSnapshot = new PageSnapshot(
                "s1", "https://app.example.com/billing",
                "<html><body><button data-testid=\"invoice-btn\">Invoices</button></body></html>",
                List.of(), Instant.now(), false
        );

        ParsedTestPlan plan = new ParsedTestPlan(
                "billing", "Download invoice",
                List.of(new RecordedAction(0, "click", "e1", null, "s1")),
                List.of(rawSnapshot),
                false, false
        );

        TestGenerationJob job = TestGenerationJob.newJob(
                UUID.randomUUID(), "v2:billing-inv-1", "billing", "Download invoice",
                "bundle-hash-1", "claude-sonnet-5", tempDir
        );

        ContextAssemblyPhase.ContextAssemblyResult result = phase.execute(job, plan);

        assertThat(result).isNotNull();
        assertThat(result.prunedSnapshots()).hasSize(1);
        assertThat(result.prunedSnapshots().get(0).prunedHtml()).contains("invoice-btn");

        ExistingCodeContext codeContext = result.codeContext();
        assertThat(codeContext.targetArea()).isEqualTo("billing");
        assertThat(codeContext.pageObjects()).hasSize(1);
        assertThat(codeContext.pageObjects().get(0).className()).isEqualTo("InvoicePage");
    }

    @Test
    @DisplayName("ContextAssemblyPhase degrades gracefully to empty ExistingCodeContext when directory is missing")
    void testDegradesGracefullyOnMissingDir(@TempDir Path tempDir) {
        ContextAssemblyPhase phase = new ContextAssemblyPhase(
                new DomPruner(), new ExistingPageObjectAnalyzer(), tempDir.resolve("non-existent")
        );

        ExistingCodeContext context = phase.execute("unknownArea");
        assertThat(context).isNotNull();
        assertThat(context.pageObjects()).isEmpty();
    }
}
