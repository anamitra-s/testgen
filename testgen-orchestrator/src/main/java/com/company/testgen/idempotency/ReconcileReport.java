package com.company.testgen.idempotency;

import java.util.List;
import java.util.UUID;

/**
 * Summary metrics of a Reconciler sweep (§7.7).
 */
public record ReconcileReport(
        int orphanedJobsMarked,
        int pendingEffectsReconciled,
        int pendingEffectsAbandoned,
        int invariantViolations,
        List<UUID> invariantJobIds
) {
    public ReconcileReport(int orphanedJobsMarked, int pendingEffectsReconciled, int pendingEffectsAbandoned, int invariantViolations) {
        this(orphanedJobsMarked, pendingEffectsReconciled, pendingEffectsAbandoned, invariantViolations, List.of());
    }
}
