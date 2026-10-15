package com.company.testgen.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransportRetryPolicyTest {

    @Test
    @DisplayName("Successfully retries transient HTTP 429 and 5xx errors with backoff and jitter")
    void testRetryOnTransientErrors() {
        List<Long> sleepDelays = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger(0);

        TransportRetryPolicy policy = new TransportRetryPolicy(
                3,
                Duration.ofMillis(100),
                Duration.ofSeconds(5),
                2.0,
                sleepDelays::add
        );

        String result = policy.execute(() -> {
            int current = attempts.incrementAndGet();
            if (current == 1) {
                throw new RuntimeException("HTTP 429 Too Many Requests");
            }
            if (current == 2) {
                throw new RuntimeException("HTTP 503 Service Unavailable");
            }
            return "SUCCESS";
        });

        assertThat(result).isEqualTo("SUCCESS");
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(sleepDelays).hasSize(2);
        assertThat(sleepDelays.get(0)).isGreaterThan(0);
        assertThat(sleepDelays.get(1)).isGreaterThan(0);
    }

    @Test
    @DisplayName("Fails fast on non-retryable errors (e.g. 400 Bad Request, NullPointerException)")
    void testNonRetryableErrorsFailFast() {
        List<Long> sleepDelays = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger(0);

        TransportRetryPolicy policy = new TransportRetryPolicy(
                3,
                Duration.ofMillis(100),
                Duration.ofSeconds(5),
                2.0,
                sleepDelays::add
        );

        assertThatThrownBy(() -> policy.execute(() -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("HTTP 400 Bad Request - Invalid prompt parameter");
        })).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("400 Bad Request");

        assertThat(attempts.get()).isEqualTo(1);
        assertThat(sleepDelays).isEmpty();
    }

    @Test
    @DisplayName("Exhausts retries when transient errors persist beyond maxRetries")
    void testExhaustionAfterMaxRetries() {
        List<Long> sleepDelays = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger(0);

        TransportRetryPolicy policy = new TransportRetryPolicy(
                2,
                Duration.ofMillis(50),
                Duration.ofSeconds(1),
                2.0,
                sleepDelays::add
        );

        assertThatThrownBy(() -> policy.execute(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException("504 Gateway Timeout");
        })).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("504 Gateway Timeout");

        // 1 initial attempt + 2 retries = 3 attempts total
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(sleepDelays).hasSize(2);
    }

    @Test
    @DisplayName("Recognizes network exceptions and HTTP status codes as retryable")
    void testIsRetryable() {
        TransportRetryPolicy policy = new TransportRetryPolicy(3);

        assertThat(policy.isRetryable(new SocketTimeoutException("Read timed out"))).isTrue();
        assertThat(policy.isRetryable(new ConnectException("Connection refused"))).isTrue();
        assertThat(policy.isRetryable(new RuntimeException("Rate limit reached (429)"))).isTrue();
        assertThat(policy.isRetryable(new RuntimeException("502 Bad Gateway"))).isTrue();
        assertThat(policy.isRetryable(new RuntimeException(new SocketTimeoutException()))).isTrue();

        assertThat(policy.isRetryable(new NullPointerException())).isFalse();
        assertThat(policy.isRetryable(new IllegalArgumentException("Invalid state"))).isFalse();
        assertThat(policy.isRetryable(null)).isFalse();
    }

    @Test
    @DisplayName("Jitter bounds remain strictly within [cappedDelay / 2, cappedDelay]")
    void testJitterBounds() {
        TransportRetryPolicy policy = new TransportRetryPolicy(
                3, Duration.ofMillis(200), Duration.ofMillis(1000), 2.0
        );

        for (int i = 0; i < 50; i++) {
            long delayAttempt1 = policy.computeBackoffWithJitter(1);
            assertThat(delayAttempt1).isBetween(100L, 200L);

            long delayAttempt2 = policy.computeBackoffWithJitter(2);
            assertThat(delayAttempt2).isBetween(200L, 400L);
        }
    }
}
