package com.company.testgen.orchestrator;

/**
 * Lifecycle states for test generation pipeline jobs (§6.13, §6.14).
 */
public enum PipelineState {
    CLAIMED,
    PARSED,
    REDACTED,
    CONTEXT_ASSEMBLED,
    RESOLVING,
    RESOLVED,
    GENERATING,
    GENERATED,
    LINTING,
    VERIFYING,
    PUBLISHING,
    DONE,
    NEEDS_HUMAN_REVIEW,
    FAILED
}
