package com.company.testgen.idempotency;

import java.util.UUID;

/**
 * Fatal exception thrown when a fenced update fails due to a lease epoch mismatch (§7.4).
 * Indicates that another process has reclaimed the job lease.
 * Standard exit code: 41 (§7.4, §9.2).
 */
public class LeaseLostException extends RuntimeException {

    public static final int EXIT_CODE = 41;

    private final UUID jobId;
    private final long leaseEpoch;

    public LeaseLostException(UUID jobId, long leaseEpoch) {
        super(String.format("Lease epoch %d is no longer current for job %s — another process has taken over. Aborting to avoid duplicate effects.", leaseEpoch, jobId));
        this.jobId = jobId;
        this.leaseEpoch = leaseEpoch;
    }

    public LeaseLostException(String message) {
        super(message);
        this.jobId = null;
        this.leaseEpoch = -1;
    }

    public int getExitCode() {
        return EXIT_CODE;
    }

    public UUID getJobId() {
        return jobId;
    }

    public long getLeaseEpoch() {
        return leaseEpoch;
    }
}
