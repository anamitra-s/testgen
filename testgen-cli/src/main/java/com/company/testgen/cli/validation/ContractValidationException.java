package com.company.testgen.cli.validation;

/**
 * Thrown when ingestion contract validation fails (§6.3).
 */
public class ContractValidationException extends RuntimeException {
    public ContractValidationException(String message) {
        super(message);
    }
}
