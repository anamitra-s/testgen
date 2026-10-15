package com.company.testgen.idempotency;

/**
 * Interface for broadcasting operational alerts (e.g. to Slack, PagerDuty) (§7.7).
 */
@FunctionalInterface
public interface OpsAlerter {
    void alertOps(String message);
}
