package com.company.testgen.idempotency;

/**
 * Exception thrown when a fault checkpoint is triggered (§7.8).
 */
public class FaultInjectedException extends RuntimeException {

    public FaultInjectedException(String message) {
        super(message);
    }

    public FaultInjectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
