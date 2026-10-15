package com.company.testgen.eval;

import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.ResolvedLocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusAndSuiteTest {

    @Test
    @DisplayName("Loads suites.yaml and sample golden case dashboard-date-filter-001 matching §8.2")
    void testCorpusLoading() {
        Path evalsDir = Path.of("evals");
        Corpus corpus = Corpus.load(evalsDir);

        assertThat(corpus.suites()).containsKeys("smoke", "golden", "hard");

        SuiteSpec smoke = corpus.suite("smoke");
        assertThat(smoke.repeats()).isEqualTo(1);
        assertThat(smoke.selection()).isEqualTo("stratified");
        assertThat(smoke.cases()).isEqualTo("10");

        SuiteSpec golden = corpus.suite("golden");
        assertThat(golden.repeats()).isEqualTo(3);
        assertThat(golden.cases()).isEqualTo("all");

        SuiteSpec hard = corpus.suite("hard");
        assertThat(hard.repeats()).isEqualTo(5);
        assertThat(hard.filter()).contains("difficulty == hard");

        Optional<EvalCase> goldenCaseOpt = corpus.findCase("dashboard-date-filter-001");
        assertThat(goldenCaseOpt).isPresent();

        EvalCase c = goldenCaseOpt.get();
        assertThat(c.caseId()).isEqualTo("dashboard-date-filter-001");
        assertThat(c.targetArea()).isEqualTo("dashboard");
        assertThat(c.difficulty()).isEqualTo("medium");
        assertThat(c.isActive()).isTrue();
        assertThat(c.tags()).contains("modal", "table", "aria-heavy");

        // Verify loaded 5 artifacts (§8.2)
        assertThat(c.recordedTestJs()).contains("Dashboard date filter persists across reload");
        assertThat(c.recordedElements()).isNotNull();
        assertThat(c.recordedElements().contractVersion()).isEqualTo(2);
        assertThat(c.recordedElements().snapshots()).isNotEmpty();
        assertThat(c.expectedLocators()).isNotEmpty();
        assertThat(c.existingCodeContext()).isNotNull();
        assertThat(c.existingCodeContext().targetArea()).isEqualTo("dashboard");

        ResolvedLocator dateBtn = c.expectedLocators().get("date-filter-btn");
        assertThat(dateBtn).isNotNull();
        assertThat(dateBtn.strategy()).isEqualTo(LocatorStrategy.TEST_ID);
        assertThat(dateBtn.value()).isEqualTo("date-filter-button");
    }

    @Test
    @DisplayName("SuiteSpec filters and stratifies cases")
    void testSuiteSpecFiltering() {
        EvalCase easy1 = new EvalCase("c1", "dashboard", "", "", null, "easy", List.of(), "", "active", "", null, null, null, null);
        EvalCase medium1 = new EvalCase("c2", "dashboard", "", "", null, "medium", List.of(), "", "active", "", null, null, null, null);
        EvalCase hard1 = new EvalCase("c3", "billing", "", "", null, "hard", List.of(), "", "active", "", null, null, null, null);
        EvalCase hard2 = new EvalCase("c4", "auth", "", "", null, "hard", List.of(), "", "quarantined", "", null, null, null, null);

        List<EvalCase> all = List.of(easy1, medium1, hard1, hard2);

        SuiteSpec golden = SuiteSpec.golden();
        List<EvalCase> goldenFiltered = golden.filterCases(all);
        // Quarantined c4 excluded
        assertThat(goldenFiltered).containsExactly(easy1, medium1, hard1);

        SuiteSpec hardSuite = SuiteSpec.hard();
        List<EvalCase> hardFiltered = hardSuite.filterCases(all);
        assertThat(hardFiltered).containsExactly(hard1);

        SuiteSpec smoke = SuiteSpec.smoke();
        List<EvalCase> smokeFiltered = smoke.filterCases(all);
        assertThat(smokeFiltered).hasSize(3);
    }

    @Test
    @DisplayName("Quarantine case updates status in meta.yaml and memory (§8.7)")
    void testQuarantineCase(@TempDir Path tempDir) throws IOException {
        Path caseDir = tempDir.resolve("cases").resolve("test-case-001");
        Files.createDirectories(caseDir);
        Files.writeString(caseDir.resolve("meta.yaml"), "caseId: test-case-001\nstatus: active\n");
        Files.writeString(tempDir.resolve("suites.yaml"), "suites:\n  golden: { repeats: 3 }\n");

        Corpus corpus = Corpus.load(tempDir);
        EvalCase c = corpus.findCase("test-case-001").orElseThrow();
        assertThat(c.isActive()).isTrue();

        corpus.quarantineCase("test-case-001");
        EvalCase updated = corpus.findCase("test-case-001").orElseThrow();
        assertThat(updated.isQuarantined()).isTrue();

        String updatedMeta = Files.readString(caseDir.resolve("meta.yaml"));
        assertThat(updatedMeta).contains("status: quarantined");
    }
}
