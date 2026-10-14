package com.company.testgen.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Executes LLM operations with exponential backoff on transient transport failures (§6.10).
 */
public class TransportRetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(TransportRetryPolicy.class);

    private final int maxRetries;
    private final Duration initialDelay;
    private final double backoffMultiplier;

    public TransportRetryPolicy(int maxRetries) {
        this(maxRetries, Duration.ofMillis(300), 2.0);
    }

    public TransportRetryPolicy(int maxRetries, Duration initialDelay, double backoffMultiplier) {
        this.maxRetries = Math.max(0, maxRetries);
        this.initialDelay = initialDelay != null ? initialDelay : Duration.ofMillis(300);
        this.backoffMultiplier = backoffMultiplier > 1.0 ? backoffMultiplier : 2.0;
    }

    public <T> T execute(Supplier<T> operation) {
        int attempt = 0;
        long currentDelayMs = initialDelay.toMillis();

        while (true) {
            try {
                return operation.get();
            } catch (RuntimeException e) {
                attempt++;
                if (attempt > maxRetries || !isRetryable(e)) {
                    throw e;
                }
                log.warn("Transient transport failure on attempt {}/{}: {}. Backing off for {}ms",
                        attempt, maxRetries, e.getMessage(), currentDelayMs);
                try {
                    Thread.sleep(currentDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during transport retry backoff", ie);
                }
                currentDelayMs = (long) (currentDelayMs * backoffMultiplier);
            }
        }
    }

    public int maxRetries() {
        return maxRetries;
    }

    protected boolean isRetryable(Throwable t) {
        String msg = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
        if (msg.contains("429") || msg.contains("rate limit") || msg.contains("too many requests")
                || msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504")
                || msg.contains("timeout") || msg.contains("timed out") || msg.contains("connection reset")) {
            return true;
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            return isRetryable(cause);
        }
        return false;
    }
}
