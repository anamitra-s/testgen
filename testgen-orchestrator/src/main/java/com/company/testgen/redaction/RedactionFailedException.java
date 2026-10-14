package com.company.testgen.redaction;

/**
 * Thrown when redaction fails (§6.9).
 * <p>
 * The redaction engine is the only component in the system that fails closed.
 * A validator that fails open costs a lower-confidence MR.
 * A redactor that fails open costs a data-exposure incident.
 */
public class RedactionFailedException extends RuntimeException {

    public RedactionFailedException(String message) {
        super(message);
    }

    public RedactionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
