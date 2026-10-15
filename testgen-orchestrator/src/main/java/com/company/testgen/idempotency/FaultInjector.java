package com.company.testgen.idempotency;

/**
 * Test-only fault injector that throws at named checkpoints, driven by a system property (§7.8).
 * Enables mid-process kills for testing idempotency and reconciliation (IT-03, IT-04, IT-05).
 */
public final class FaultInjector {

    public static final String FAULT_PROPERTY = "testgen.fault.checkpoint";

    private FaultInjector() {}

    /**
     * Checks if a fault is configured for the given checkpoint name.
     * If so, throws {@link FaultInjectedException}.
     *
     * @param checkpointName name of the checkpoint (e.g. "after-branch-creation", "after-mr-created-before-confirm")
     * @throws FaultInjectedException if the system property matches this checkpoint
     */
    public static void checkpoint(String checkpointName) {
        if (checkpointName == null) {
            return;
        }
        String active = System.getProperty(FAULT_PROPERTY);
        if (active != null && (active.equalsIgnoreCase(checkpointName) || "all".equalsIgnoreCase(active))) {
            throw new FaultInjectedException("Deliberate mid-process kill at checkpoint: " + checkpointName);
        }
    }

    /**
     * Sets the active checkpoint name to trigger a fault.
     */
    public static void setCheckpoint(String checkpointName) {
        if (checkpointName == null) {
            System.clearProperty(FAULT_PROPERTY);
        } else {
            System.setProperty(FAULT_PROPERTY, checkpointName);
        }
    }

    /**
     * Clears all active fault checkpoints.
     */
    public static void clear() {
        System.clearProperty(FAULT_PROPERTY);
    }
}
