package com.company.testgen.phase;

import com.company.testgen.llm.LlmClient;
import com.company.testgen.phases.LintPhase;

/**
 * Package alias for {@link com.company.testgen.phases.GeneratePhase}.
 */
public class GeneratePhase extends com.company.testgen.phases.GeneratePhase {
    public GeneratePhase(LlmClient llm, LintPhase lintPhase) {
        super(llm, lintPhase);
    }
}
