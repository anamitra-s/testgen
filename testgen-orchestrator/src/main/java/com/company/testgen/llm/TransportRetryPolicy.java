package com.company.testgen.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Executes LLM operations with exponential backoff and jitter on transient transport failures (§6.10, NFR-11, §9.1).
 * Specifically handles HTTP 429 rate limits, 5xx server errors, timeouts, and network drops,
 * kept strictly distinct from the semantic retry loop.
 */
public class TransportRetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(TransportRetryPolicy.class);

    private final int maxRetries;
    private final Duration backoffBase;
    private final Duration maxDelay;
    private final double backoffMultiplier;
    private final Consumer<Long> sleeper;

    public TransportRetryPolicy(int maxRetries) {
        this(maxRetries, Duration.ofSeconds(2));
    }

    public TransportRetryPolicy(int maxRetries, Duration backoffBase) {
        this(maxRetries, backoffBase, Duration.ofSeconds(30), 2.0, TransportRetryPolicy::defaultSleep);
    }

    public TransportRetryPolicy(int maxRetries, Duration backoffBase, Duration maxDelay, double backoffMultiplier) {
        this(maxRetries, backoffBase, maxDelay, backoffMultiplier, TransportRetryPolicy::defaultSleep);
    }

    public TransportRetryPolicy(int maxRetries, Duration backoffBase, Duration maxDelay, double backoffMultiplier, Consumer<Long> sleeper) {
        this.maxRetries = Math.max(0, maxRetries);
        this.backoffBase = backoffBase != null ? backoffBase : Duration.ofSeconds(2);
        this.maxDelay = maxDelay != null ? maxDelay : Duration.ofSeconds(30);
        this.backoffMultiplier = backoffMultiplier >= 1.0 ? backoffMultiplier : 2.0;
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper cannot be null");
    }

    public <T> T execute(Supplier<T> operation) {
        int attempt = 0;

        while (true) {
            try {
                return operation.get();
            } catch (RuntimeException e) {
                attempt++;
                if (attempt > maxRetries || !isRetryable(e)) {
                    throw e;
                }

                long delayMs = computeBackoffWithJitter(attempt);
                log.warn("Transient transport failure on attempt {}/{}: {}. Backing off for {}ms (with jitter) [NFR-11]",
                        attempt, maxRetries, e.getMessage(), delayMs);

                sleeper.accept(delayMs);
            }
        }
    }

    public void executeRunnable(Runnable runnable) {
        execute(() -> {
            runnable.run();
            return null;
        });
    }

    /**
     * Computes exponential backoff with full jitter (§6.10, NFR-11).
     * Backoff = min(maxDelay, backoffBase * (multiplier ^ (attempt - 1)))
     * Jitter = uniform random in [backoff / 2, backoff]
     */
    public long computeBackoffWithJitter(int attempt) {
        long baseMs = backoffBase.toMillis();
        long maxMs = maxDelay.toMillis();

        double exponential = baseMs * Math.pow(backoffMultiplier, Math.max(0, attempt - 1));
        long cappedDelay = Math.min(maxMs, (long) exponential);

        long minJitter = cappedDelay / 2;
        if (minJitter >= cappedDelay) {
            return cappedDelay;
        }

        return ThreadLocalRandom.current().nextLong(minJitter, cappedDelay + 1);
    }

    public boolean isRetryable(Throwable t) {
        if (t == null) {
            return false;
        }

        if (t instanceof SocketTimeoutException
                || t instanceof TimeoutException
                || t instanceof ConnectException) {
            return true;
        }

        String msg = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
        if (msg.contains("429")
                || msg.contains("rate limit")
                || msg.contains("too many requests")
                || msg.contains("500")
                || msg.contains("502")
                || msg.contains("503")
                || msg.contains("504")
                || msg.contains("service unavailable")
                || msg.contains("bad gateway")
                || msg.contains("gateway timeout")
                || msg.contains("internal server error")
                || msg.contains("timeout")
                || msg.contains("timed out")
                || msg.contains("connection reset")
                || msg.contains("connection refused")
                || msg.contains("broken pipe")) {
            return true;
        }

        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            return isRetryable(cause);
        }

        return false;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public Duration backoffBase() {
        return backoffBase;
    }

    public Duration maxDelay() {
        return maxDelay;
    }

    private static void defaultSleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during transport retry backoff", ie);
        }
    }
}
