package com.company.testgen.eval;

import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.phase.ContextAssemblyPhase;

import java.util.List;

/**
 * Context assembly phase implementation that returns the case's frozen ExistingCodeContext (§8.2, §8.5).
 * Guarantees that code context is isolated and invariant across runs.
 */
public class FrozenContextAssemblyPhase extends ContextAssemblyPhase {

    private final ExistingCodeContext frozen;

    public FrozenContextAssemblyPhase(ExistingCodeContext frozen) {
        this.frozen = frozen != null ? frozen : ExistingCodeContext.empty();
    }

    @Override
    public ExistingCodeContext execute(String targetArea) {
        return frozen;
    }

    @Override
    public ContextAssemblyResult execute(TestGenerationJob job, ParsedTestPlan plan) {
        return new ContextAssemblyResult(
                frozen,
                plan != null && plan.snapshots() != null ? plan.snapshots() : List.of(),
                plan
        );
    }
}
