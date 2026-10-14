package com.company.testgen.model;

/**
 * Pipeline execution phases (§6.2, §6.10, §6.13).
 */
public enum Phase {
    PARSE,
    REDACT,
    CONTEXT_ASSEMBLY,
    RESOLVE,
    GENERATE,
    LINT,
    VERIFY
}
