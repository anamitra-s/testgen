package com.company.testgen.orchestrator;

/**
 * Base unchecked exception for pipeline phase execution failures (§6.11).
 */
public class PipelineException extends RuntimeException {

    public PipelineException(String message) {
        super(message);
    }

    public PipelineException(String message, Throwable cause) {
        super(message, cause);
    }
}
