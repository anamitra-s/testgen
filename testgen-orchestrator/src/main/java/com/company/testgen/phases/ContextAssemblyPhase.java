package com.company.testgen.phases;

import com.company.testgen.dom.DomPruner;
import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.PageSnapshot;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.validation.ExistingPageObjectAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Phase 3: Assembly of existing code context and page object digests (§3.6, §6.11, §6.13, FR-07, FR-16).
 * Integrates DomPruner to bound DOM snapshots and ExistingPageObjectAnalyzer to extract reusable AST digests.
 * Degrades gracefully on analysis failure (empty context, log warning).
 */
public class ContextAssemblyPhase {

    private static final Logger log = LoggerFactory.getLogger(ContextAssemblyPhase.class);

    private final DomPruner domPruner;
    private final ExistingPageObjectAnalyzer analyzer;
    private final Path repoRoot;

    public record ContextAssemblyResult(
            ExistingCodeContext codeContext,
            List<PageSnapshot> prunedSnapshots,
            ParsedTestPlan plan
    ) {}

    public ContextAssemblyPhase() {
        this(new DomPruner(), new ExistingPageObjectAnalyzer(), Path.of("."));
    }

    public ContextAssemblyPhase(DomPruner domPruner, ExistingPageObjectAnalyzer analyzer) {
        this(domPruner, analyzer, Path.of("."));
    }

    public ContextAssemblyPhase(DomPruner domPruner, ExistingPageObjectAnalyzer analyzer, Path repoRoot) {
        this.domPruner = Objects.requireNonNull(domPruner, "domPruner cannot be null");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer cannot be null");
        this.repoRoot = repoRoot != null ? repoRoot : Path.of(".");
    }

    /**
     * Backward-compatible entry point for analyzing targetArea.
     * Degrades gracefully to empty context on error (§3.6).
     */
    public ExistingCodeContext execute(String targetArea) {
        log.info("Executing ContextAssemblyPhase for targetArea={}", targetArea);
        try {
            Path poDir = resolvePageObjectDir(targetArea);
            return analyzer.analyze(poDir, null, targetArea);
        } catch (Exception e) {
            log.warn("Failed to analyze existing page objects for targetArea={}; degrading to empty context: {}",
                    targetArea, e.getMessage());
            return new ExistingCodeContext(targetArea != null ? targetArea : "", List.of());
        }
    }

    /**
     * Executes context assembly for the given job and parsed/redacted test plan.
     * Prunes DOM snapshots and performs static analysis on existing Page Objects.
     */
    public ContextAssemblyResult execute(TestGenerationJob job, ParsedTestPlan plan) {
        String targetArea = job != null ? job.targetArea() : (plan != null ? plan.targetArea() : "");
        String treeSha = job != null ? job.baseCommitSha() : null;
        log.info("Executing ContextAssemblyPhase for job {} (targetArea={}, treeSha={})",
                job != null ? job.id() : "n/a", targetArea, treeSha);

        // 1. Prune DOM snapshots using DomPruner (FR-07)
        List<PageSnapshot> prunedSnapshots = List.of();
        if (plan != null && plan.snapshots() != null) {
            prunedSnapshots = plan.snapshots().stream()
                    .map(domPruner::prune)
                    .toList();
        }

        // 2. Perform existing code reuse analysis using ExistingPageObjectAnalyzer (FR-16)
        ExistingCodeContext codeContext;
        try {
            Path poDir = resolvePageObjectDir(targetArea);
            codeContext = analyzer.analyze(poDir, treeSha, targetArea);
        } catch (Exception e) {
            log.warn("Existing Page Object analysis failed for targetArea={}; degrading to empty context: {}",
                    targetArea, e.getMessage());
            codeContext = new ExistingCodeContext(targetArea != null ? targetArea : "", List.of());
        }

        // 3. Assemble updated plan with pruned snapshots
        ParsedTestPlan updatedPlan = plan != null
                ? new ParsedTestPlan(
                        plan.targetArea(),
                        plan.testDescription(),
                        plan.actions(),
                        prunedSnapshots,
                        plan.containsLoginFlow(),
                        plan.mutatesData()
                )
                : null;

        return new ContextAssemblyResult(codeContext, prunedSnapshots, updatedPlan);
    }

    /**
     * Prunes a list of page snapshots and extracts code context for the target area.
     */
    public ContextAssemblyResult execute(String targetArea, String treeSha, List<PageSnapshot> snapshots) {
        List<PageSnapshot> pruned = snapshots != null
                ? snapshots.stream().map(domPruner::prune).toList()
                : List.of();

        ExistingCodeContext context;
        try {
            Path poDir = resolvePageObjectDir(targetArea);
            context = analyzer.analyze(poDir, treeSha, targetArea);
        } catch (Exception e) {
            log.warn("Existing Page Object analysis failed for targetArea={}; degrading to empty context: {}",
                    targetArea, e.getMessage());
            context = new ExistingCodeContext(targetArea != null ? targetArea : "", List.of());
        }

        return new ContextAssemblyResult(context, pruned, null);
    }

    private Path resolvePageObjectDir(String targetArea) {
        if (targetArea != null && !targetArea.isBlank()) {
            Path candidate = repoRoot.resolve("src/test/java/com/company/testgen/pages/" + targetArea);
            if (Files.exists(candidate)) {
                return candidate;
            }
            Path candidate2 = repoRoot.resolve("pages/" + targetArea);
            if (Files.exists(candidate2)) {
                return candidate2;
            }
        }
        return repoRoot;
    }

    public DomPruner domPruner() {
        return domPruner;
    }

    public ExistingPageObjectAnalyzer analyzer() {
        return analyzer;
    }

    public Path repoRoot() {
        return repoRoot;
    }
}
