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
import java.util.Map;
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
    @DisplayName("smoke clamps selection to available active cases when fewer than 10 cases exist (Assumption A-04)")
    void testSmokeClampingOnSmallCorpus() {
        Path evalsDir = Path.of("evals");
        Corpus corpus = Corpus.load(evalsDir);

        SuiteSpec smoke = corpus.suite("smoke");
        assertThat(smoke.cases()).isEqualTo("10");

        // When only 1 active case exists in evals/cases, load(smoke) must not throw IndexOutOfBoundsException
        // and must clamp to Math.min(10, activeCases.size()) = 1
        List<EvalCase> smokeCases = corpus.load(smoke);
        assertThat(smokeCases).hasSize(Math.min(10, corpus.activeCases().size()));
        assertThat(smokeCases).allMatch(EvalCase::isActive);
    }

    @Test
    @DisplayName("Corpus.load excludes quarantined and retired cases from suite execution (§8.7)")
    void testQuarantinedAndRetiredCasesExcluded() {
        EvalCase activeCase = new EvalCase("active-1", "dashboard", "", "", null, "easy", List.of(), "", EvalCase.STATUS_ACTIVE, "", null, null, null, null);
        EvalCase quarantinedCase = new EvalCase("quarantined-1", "dashboard", "", "", null, "medium", List.of(), "", EvalCase.STATUS_QUARANTINED, "", null, null, null, null);
        EvalCase retiredCase = new EvalCase("retired-1", "composer", "", "", null, "hard", List.of(), "", EvalCase.STATUS_RETIRED, "", null, null, null, null);

        Corpus customCorpus = new Corpus(Path.of("evals"), Map.of(), List.of(activeCase, quarantinedCase, retiredCase));

        assertThat(customCorpus.activeCases()).containsExactly(activeCase);

        SuiteSpec smoke = SuiteSpec.smoke();
        List<EvalCase> smokeLoaded = customCorpus.load(smoke);
        assertThat(smokeLoaded).containsExactly(activeCase);

        SuiteSpec golden = SuiteSpec.golden();
        List<EvalCase> goldenLoaded = customCorpus.load(golden);
        assertThat(goldenLoaded).containsExactly(activeCase);
    }

    @Test
    @DisplayName("smoke performs stratified sampling across strata when 10+ cases exist using @TempDir")
    void testStratifiedSamplingWithTenPlusCasesUsingTempDir(@TempDir Path tempDir) throws IOException {
        Path casesDir = tempDir.resolve("cases");
        Files.createDirectories(casesDir);
        Files.writeString(tempDir.resolve("suites.yaml"), "suites:\n  smoke:\n    cases: 10\n    selection: stratified\n");

        // Create 9 active cases across multiple targetAreas and difficulties (3 areas x 3 difficulties)
        String[] areas = {"dashboard", "composer", "billing"};
        String[] difficulties = {"easy", "medium", "hard"};
        int id = 1;
        for (String area : areas) {
            for (String diff : difficulties) {
                Path dir = casesDir.resolve("case-" + id++);
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("meta.yaml"),
                        String.format("caseId: %s\ntargetArea: %s\ndifficulty: %s\nstatus: active\n",
                                dir.getFileName(), area, diff));
            }
        }

        // Add 1 quarantined case and 1 retired case to verify exclusion
        Path qDir = casesDir.resolve("case-quarantined");
        Files.createDirectories(qDir);
        Files.writeString(qDir.resolve("meta.yaml"), "caseId: case-quarantined\ntargetArea: dashboard\ndifficulty: easy\nstatus: quarantined\n");

        Path rDir = casesDir.resolve("case-retired");
        Files.createDirectories(rDir);
        Files.writeString(rDir.resolve("meta.yaml"), "caseId: case-retired\ntargetArea: composer\ndifficulty: medium\nstatus: retired\n");

        Corpus corpus = Corpus.load(tempDir);
        assertThat(corpus.activeCases()).hasSize(9); // 3x3 active cases

        SuiteSpec smoke = corpus.suite("smoke");
        List<EvalCase> selected = corpus.load(smoke);

        // Clamped to active count (Math.min(10, 9) = 9)
        assertThat(selected).hasSize(9);
        assertThat(selected).allMatch(EvalCase::isActive);
        assertThat(selected).noneMatch(c -> c.caseId().equals("case-quarantined") || c.caseId().equals("case-retired"));

        // Now add more cases so active count exceeds 10 (15 active cases total)
        for (int i = 10; i <= 15; i++) {
            Path dir = casesDir.resolve("extra-case-" + i);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("meta.yaml"),
                    String.format("caseId: %s\ntargetArea: dashboard\ndifficulty: easy\nstatus: active\n",
                            dir.getFileName()));
        }

        Corpus largerCorpus = Corpus.load(tempDir);
        assertThat(largerCorpus.activeCases()).hasSize(15);

        List<EvalCase> smoke10 = largerCorpus.load(smoke);
        assertThat(smoke10).hasSize(10);
        assertThat(smoke10).allMatch(EvalCase::isActive);
        assertThat(smoke10).noneMatch(c -> c.caseId().equals("case-quarantined") || c.caseId().equals("case-retired"));
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
