package com.company.testgen.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background heartbeat extending {@code lease_expires_at} every {@code ttl / 3} using Java 21 virtual threads (§7.4).
 * If a {@link LeaseLostException} occurs during heartbeat, invokes {@code onLost} (interrupting the main thread)
 * and halts execution.
 */
public class LeaseHeartbeat implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LeaseHeartbeat.class);

    private final ScheduledExecutorService exec;
    private final FencedJobWriter writer;
    private final Duration ttl;
    private final Runnable onLost;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LeaseHeartbeat(FencedJobWriter writer, Duration ttl, Runnable onLost) {
        this.writer = Objects.requireNonNull(writer, "writer cannot be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl cannot be null");
        this.onLost = Objects.requireNonNull(onLost, "onLost cannot be null");

        this.exec = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("lease-heartbeat").factory());

        long intervalMs = Math.max(1, ttl.toMillis() / 3);
        this.exec.scheduleAtFixedRate(this::beat, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("LeaseHeartbeat started: interval={}ms for job {} at epoch {}",
                intervalMs, writer.getJobId(), writer.getLeaseEpoch());
    }

    public LeaseHeartbeat(FencedJobWriter writer, Duration ttl) {
        this(writer, ttl, Thread.currentThread());
    }

    public LeaseHeartbeat(FencedJobWriter writer, Duration ttl, Thread targetThread) {
        this(writer, ttl, () -> {
            log.error("Lease lost for job {}! Interrupting target thread {}", writer.getJobId(), targetThread.getName());
            targetThread.interrupt();
        });
    }

    private void beat() {
        if (closed.get()) {
            return;
        }
        try {
            writer.touch();
        } catch (LeaseLostException e) {
            log.error("Lease lost during heartbeat for job {} at epoch {}: triggering onLost handler",
                    writer.getJobId(), writer.getLeaseEpoch(), e);
            try {
                onLost.run();
            } finally {
                close();
            }
        } catch (Throwable t) {
            log.warn("Non-fatal error during heartbeat touch for job {}: {}", writer.getJobId(), t.getMessage(), t);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            exec.shutdownNow();
            log.debug("LeaseHeartbeat closed for job {}", writer.getJobId());
        }
    }
}
