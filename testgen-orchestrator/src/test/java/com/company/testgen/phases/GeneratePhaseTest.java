package com.company.testgen.phases;

import com.company.testgen.llm.GenerateRequest;
import com.company.testgen.llm.LlmClient;
import com.company.testgen.llm.ResolveRequest;
import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.GeneratedFile;
import com.company.testgen.model.GeneratedFileSet;
import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratePhaseTest {

    private ParsedTestPlan plan;
    private ResolvedLocatorSet locators;
    private ExistingCodeContext context;

    @BeforeEach
    void setUp() {
        plan = new ParsedTestPlan("billing", "Billing submission", List.of(), List.of(), false, false);
        locators = ResolvedLocatorSet.of(
                new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "submit-btn", null, "page.getByTestId(\"submit-btn\")")
        );
        context = new ExistingCodeContext("billing", List.of());
    }

    @Test
    @DisplayName("GeneratePhase succeeds on first attempt when LintPhase passes")
    void testGeneratePhaseSuccessFirstPass() {
        GeneratedFile validTest = new GeneratedFile(
                "src/test/java/com/company/tests/billing/BillingTest.java",
                """
                package com.company.tests.billing;
                import org.junit.jupiter.api.Test;
                public class BillingTest {
                    @Test
                    public void testSubmit() {
                        page.getByTestId("submit-btn");
                    }
                }
                """,
                true
        );

        AtomicInteger callCount = new AtomicInteger(0);
        LlmClient fakeLlm = new LlmClient() {
            @Override
            public ResolvedLocatorSet resolveLocators(ResolveRequest request) {
                return locators;
            }

            @Override
            public GeneratedFileSet generateCode(GenerateRequest request) {
                callCount.incrementAndGet();
                return new GeneratedFileSet(List.of(validTest));
            }
        };

        GeneratePhase generatePhase = new GeneratePhase(fakeLlm, new LintPhase());
        List<GeneratedFile> files = generatePhase.execute(plan, locators, context, "sha-1");

        assertThat(files).hasSize(1);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("GeneratePhase retries once with lint feedback when first pass fails, and succeeds")
    void testGeneratePhaseRecoversAfterOneRegeneration() {
        GeneratedFile invalidSyntax = new GeneratedFile(
                "src/test/java/com/company/tests/billing/BillingTest.java",
                "package com.company.tests.billing; public class BillingTest { incomplete syntax",
                true
        );

        GeneratedFile validTest = new GeneratedFile(
                "src/test/java/com/company/tests/billing/BillingTest.java",
                """
                package com.company.tests.billing;
                import org.junit.jupiter.api.Test;
                public class BillingTest {
                    @Test
                    public void testSubmit() {
                        page.getByTestId("submit-btn");
                    }
                }
                """,
                true
        );

        List<GenerateRequest> requests = new ArrayList<>();
        LlmClient fakeLlm = new LlmClient() {
            @Override
            public ResolvedLocatorSet resolveLocators(ResolveRequest request) {
                return locators;
            }

            @Override
            public GeneratedFileSet generateCode(GenerateRequest request) {
                requests.add(request);
                if (requests.size() == 1) {
                    return new GeneratedFileSet(List.of(invalidSyntax));
                } else {
                    return new GeneratedFileSet(List.of(validTest));
                }
            }
        };

        GeneratePhase generatePhase = new GeneratePhase(fakeLlm, new LintPhase());
        List<GeneratedFile> files = generatePhase.execute(plan, locators, context, "sha-1");

        assertThat(files).hasSize(1);
        assertThat(requests).hasSize(2);

        // First attempt had no lint feedback
        assertThat(requests.get(0).feedback()).isNull();
        assertThat(requests.get(0).attempt()).isEqualTo(0);

        // Second attempt had lint feedback from first failure
        assertThat(requests.get(1).feedback()).isNotNull();
        assertThat(requests.get(1).feedback()).contains("JavaParser syntax parse error");
        assertThat(requests.get(1).attempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("GeneratePhase throws EscalationRequired when second pass also fails lint")
    void testGeneratePhaseEscalatesWhenRegenerationFails() {
        GeneratedFile invalidSyntax = new GeneratedFile(
                "src/test/java/com/company/tests/billing/BillingTest.java",
                "package com.company.tests.billing; public class BillingTest { incomplete syntax",
                true
        );

        List<GenerateRequest> requests = new ArrayList<>();
        LlmClient fakeLlm = new LlmClient() {
            @Override
            public ResolvedLocatorSet resolveLocators(ResolveRequest request) {
                return locators;
            }

            @Override
            public GeneratedFileSet generateCode(GenerateRequest request) {
                requests.add(request);
                return new GeneratedFileSet(List.of(invalidSyntax));
            }
        };

        GeneratePhase generatePhase = new GeneratePhase(fakeLlm, new LintPhase());

        assertThatThrownBy(() -> generatePhase.execute(plan, locators, context, "sha-1"))
                .isInstanceOf(EscalationRequired.class)
                .satisfies(e -> {
                    EscalationRequired er = (EscalationRequired) e;
                    assertThat(er.reasonCode()).isEqualTo("LINT_FAILURE");
                    assertThat(er.report()).contains("JavaParser syntax parse error");
                });

        // Verifies AT MOST ONE regeneration pass (total 2 LLM calls) (§6.12)
        assertThat(requests).hasSize(2);
    }
}
