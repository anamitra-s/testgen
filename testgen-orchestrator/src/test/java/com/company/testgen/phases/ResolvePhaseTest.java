package com.company.testgen.phases;

import com.company.testgen.llm.GenerateRequest;
import com.company.testgen.llm.LlmClient;
import com.company.testgen.llm.ResolveRequest;
import com.company.testgen.model.DomElementSnapshot;
import com.company.testgen.model.ElementFingerprint;
import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.GeneratedFileSet;
import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.PageSnapshot;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.RecordedAction;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.orchestrator.CorrectiveFeedbackBuilder;
import com.company.testgen.persistence.InMemoryRetryAttemptRepository;
import com.company.testgen.persistence.InMemoryValidationResultRepository;
import com.company.testgen.validation.LocatorGrammarValidator;
import com.company.testgen.validation.StaticDomValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResolvePhaseTest {

    @Test
    @DisplayName("ResolvePhase completes on first attempt when all locators pass validation")
    void testResolvePhaseFirstAttemptSuccess() {
        InMemoryRetryAttemptRepository retryRepo = new InMemoryRetryAttemptRepository();
        InMemoryValidationResultRepository valRepo = new InMemoryValidationResultRepository();

        LlmClient mockLlm = new LlmClient() {
            @Override
            public ResolvedLocatorSet resolveLocators(ResolveRequest request) {
                return new ResolvedLocatorSet(List.of(
                        new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "btn-save")
                ));
            }

            @Override
            public GeneratedFileSet generateCode(GenerateRequest request) {
                return new GeneratedFileSet(List.of());
            }
        };

        ResolvePhase phase = new ResolvePhase(
                mockLlm,
                new LocatorGrammarValidator(),
                new StaticDomValidator(),
                retryRepo,
                valRepo,
                new CorrectiveFeedbackBuilder(),
                3
        );

        UUID jobId = UUID.randomUUID();
        TestGenerationJob job = new TestGenerationJob(
                jobId, "job-1", "billing", "desc", "user", "ui", "bundle-1", "model-1", "1.0", "2.1", "main", "worker", 1L, null
        );

        ElementFingerprint fp = new ElementFingerprint("button", "button", "Save", "Save", 0, "hash1");
        DomElementSnapshot el = new DomElementSnapshot("e1", "button", Map.of("data-testid", "btn-save"), "Save", "button", "Save", fp);
        PageSnapshot snap = new PageSnapshot("s1", "https://app.example.com", null, List.of(el), Instant.now());
        RecordedAction action = new RecordedAction(1, "click", "e1", "click save", "s1");
        ParsedTestPlan plan = new ParsedTestPlan("billing", "desc", List.of(action), List.of(snap), false, false);

        ResolvePhase.ResolveResult result = phase.execute(job, plan, ExistingCodeContext.empty());

        assertThat(result.validation().passed()).isTrue();
        assertThat(result.attemptsUsed()).isEqualTo(1);
        assertThat(result.accepted().size()).isEqualTo(1);
        assertThat(retryRepo.findByJobId(jobId)).hasSize(1);
    }

    @Test
    @DisplayName("ResolvePhase enforces locator freezing in CODE when retrying")
    void testResolvePhaseLocatorFreezing() {
        InMemoryRetryAttemptRepository retryRepo = new InMemoryRetryAttemptRepository();
        InMemoryValidationResultRepository valRepo = new InMemoryValidationResultRepository();

        UUID jobId = UUID.randomUUID();
        TestGenerationJob job = new TestGenerationJob(
                jobId, "job-2", "billing", "desc", "user", "ui", "bundle-1", "model-1", "1.0", "2.1", "main", "worker", 1L, null
        );

        ElementFingerprint fp1 = new ElementFingerprint("button", "button", "OK", "OK", 0, "hash1");
        DomElementSnapshot el1 = new DomElementSnapshot("e1", "button", Map.of("data-testid", "btn-ok"), "OK", "button", "OK", fp1);

        ElementFingerprint fp2 = new ElementFingerprint("input", "textbox", "Name", "Name", 0, "hash2");
        DomElementSnapshot el2 = new DomElementSnapshot("e2", "input", Map.of("data-testid", "field-name"), "Name", "textbox", "Name", fp2);

        PageSnapshot snap = new PageSnapshot("s1", "https://app.example.com", null, List.of(el1, el2), Instant.now());
        RecordedAction a1 = new RecordedAction(1, "click", "e1", "click ok", "s1");
        RecordedAction a2 = new RecordedAction(2, "fill", "e2", "fill name", "s1");
        ParsedTestPlan plan = new ParsedTestPlan("billing", "desc", List.of(a1, a2), List.of(snap), false, false);

        AtomicInteger callCount = new AtomicInteger(0);
        LlmClient mockLlm = new LlmClient() {
            @Override
            public ResolvedLocatorSet resolveLocators(ResolveRequest request) {
                int call = callCount.incrementAndGet();
                if (call == 1) {
                    // Attempt 1: e1 is valid, e2 has banned xpath
                    return new ResolvedLocatorSet(List.of(
                            new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "btn-ok"),
                            new ResolvedLocator("e2", LocatorStrategy.TEXT, "//div/input")
                    ));
                } else {
                    // Attempt 2: model attempts to mutate e1 AND fix e2
                    return new ResolvedLocatorSet(List.of(
                            new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "changed-btn"),
                            new ResolvedLocator("e2", LocatorStrategy.TEST_ID, "field-name")
                    ));
                }
            }

            @Override
            public GeneratedFileSet generateCode(GenerateRequest request) {
                return new GeneratedFileSet(List.of());
            }
        };

        ResolvePhase phase = new ResolvePhase(
                mockLlm,
                new LocatorGrammarValidator(),
                new StaticDomValidator(),
                retryRepo,
                valRepo,
                new CorrectiveFeedbackBuilder(),
                3
        );

        ResolvePhase.ResolveResult result = phase.execute(job, plan, ExistingCodeContext.empty());

        assertThat(result.validation().passed()).isTrue();
        assertThat(result.attemptsUsed()).isEqualTo(2);
        // e1 should retain the value from attempt 1 ("btn-ok"), NOT the attempted overwrite ("changed-btn")
        ResolvedLocator e1 = result.accepted().findByElementRefId("e1").orElse(null);
        assertThat(e1).isNotNull();
        assertThat(e1.value()).isEqualTo("btn-ok");

        // e2 should now be accepted as "field-name"
        ResolvedLocator e2 = result.accepted().findByElementRefId("e2").orElse(null);
        assertThat(e2).isNotNull();
        assertThat(e2.value()).isEqualTo("field-name");
    }
}
