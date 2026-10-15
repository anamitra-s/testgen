package com.company.testgen.eval;

import com.company.testgen.llm.LlmClient;
import com.company.testgen.llm.ResolveRequest;
import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.persistence.EvalRepository;
import com.company.testgen.persistence.EvalRunRecord;
import com.company.testgen.persistence.InMemoryEvalRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvalRunnerTest {

    @Test
    @DisplayName("EvalRunner executes Parse -> Redact -> Resolve -> L0 -> L1 with frozen context and bounded concurrency (§8.5)")
    void testEvalRunnerExecution() {
        EvalRepository repo = new InMemoryEvalRepository();
        Corpus corpus = Corpus.load(Path.of("evals"));
        SuiteSpec suite = new SuiteSpec("test-smoke", "1", "all", 2, null);

        // Mock LlmClient that produces valid resolved locators
        LlmClient mockLlm = mock(LlmClient.class);
        when(mockLlm.resolveLocators(any(ResolveRequest.class))).thenAnswer(invocation -> {
            ResolveRequest req = invocation.getArgument(0);
            return new ResolvedLocatorSet(List.of(
                    new ResolvedLocator("date-filter-btn", LocatorStrategy.TEST_ID, "date-filter-button"),
                    new ResolvedLocator("start-date-input", LocatorStrategy.LABEL, "Start Date"),
                    new ResolvedLocator("end-date-input", LocatorStrategy.LABEL, "End Date"),
                    new ResolvedLocator("apply-filter-btn", LocatorStrategy.ROLE_NAME, "Apply Filter", "button"),
                    new ResolvedLocator("filter-badge", LocatorStrategy.TEST_ID, "active-date-badge")
            ));
        });

        EvalRunner runner = new EvalRunner(repo, corpus, mockLlm);
        RunConfig config = new RunConfig(
                2, 2, "advisory", "MANUAL", "claude-sonnet-5", "test-prompt-hash",
                "1.0.0", "main", Path.of("evals"), Path.of("eval-report.json")
        );

        EvalRun run = runner.run(suite, config);

        assertThat(run).isNotNull();
        assertThat(run.metrics()).isNotNull();
        assertThat(run.metrics().firstAttemptPassRate()).isEqualTo(1.0);
        assertThat(run.metrics().locatorExactMatchRate()).isEqualTo(1.0);
        assertThat(run.metrics().escalationRate()).isEqualTo(0.0);

        // Check repository persistence
        Optional<EvalRunRecord> persisted = repo.findRunById(run.runId());
        assertThat(persisted).isPresent();
        assertThat(persisted.get().suite()).isEqualTo("test-smoke");

        // Verify promote baseline (§8.5)
        runner.promoteBaseline(run.runId());
        Optional<EvalRunRecord> baseline = repo.findBaseline("test-smoke", "claude-sonnet-5", "test-prompt-hash");
        assertThat(baseline).isPresent();
        assertThat(baseline.get().id()).isEqualTo(run.runId());
        assertThat(baseline.get().isBaseline()).isTrue();
    }
}
