package com.company.testgen.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Distributed concurrency control for target areas using PostgreSQL session-level advisory locks (§6.1, §6.13, FR-26).
 * Holds an advisory lock on {@code hash(target_area)} across Generate, commit, and MR creation.
 * Prevents overlapping branches or duplicate commits on the same page objects/target area.
 */
public class TargetAreaLock {

    private static final Logger log = LoggerFactory.getLogger(TargetAreaLock.class);

    private final DataSource dataSource;
    private final boolean inMemory;
    private final ConcurrentHashMap<String, Semaphore> localLocks;

    public interface Lock extends AutoCloseable {
        @Override
        void close();
    }

    public TargetAreaLock(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource cannot be null");
        this.inMemory = false;
        this.localLocks = null;
    }

    public TargetAreaLock(Db db) {
        this(Objects.requireNonNull(db, "db cannot be null").dataSource());
    }

    private TargetAreaLock(boolean inMemory) {
        this.dataSource = null;
        this.inMemory = inMemory;
        this.localLocks = new ConcurrentHashMap<>();
    }

    public static TargetAreaLock inMemory() {
        return new TargetAreaLock(true);
    }

    public static TargetAreaLock create(DataSource dataSource) {
        return new TargetAreaLock(dataSource);
    }

    public static TargetAreaLock create(Db db) {
        return new TargetAreaLock(db);
    }

    /**
     * Derives a deterministic 64-bit integer hash from the target area name for pg_advisory_lock (§6.13, FR-26).
     */
    public static long hashTargetArea(String targetArea) {
        if (targetArea == null) {
            return 0L;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(targetArea.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Acquires an exclusive advisory lock for the target area, blocking until granted (§6.13, FR-26).
     *
     * @param targetArea the target area name (e.g., "dashboard", "billing")
     * @return an AutoCloseable lock handle that releases the advisory lock on close
     */
    public Lock acquire(String targetArea) {
        Objects.requireNonNull(targetArea, "targetArea cannot be null");

        if (inMemory) {
            Semaphore sem = localLocks.computeIfAbsent(targetArea, k -> new Semaphore(1));
            try {
                sem.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while acquiring in-memory lock for targetArea: " + targetArea, e);
            }
            AtomicBoolean released = new AtomicBoolean(false);
            return () -> {
                if (released.compareAndSet(false, true)) {
                    sem.release();
                }
            };
        }

        long key = hashTargetArea(targetArea);
        log.debug("Acquiring PostgreSQL advisory lock for targetArea='{}' (key={})", targetArea, key);

        try {
            Connection conn = dataSource.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement("SELECT pg_advisory_lock(?)")) {
                stmt.setLong(1, key);
                stmt.execute();
            }

            log.info("Acquired PostgreSQL advisory lock for targetArea='{}' (key={})", targetArea, key);
            return new PostgresLockHandle(conn, key, targetArea);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire PostgreSQL advisory lock for targetArea: " + targetArea, e);
        }
    }

    /**
     * Attempts to acquire an exclusive advisory lock for the target area without blocking.
     *
     * @param targetArea the target area name
     * @return Optional containing the lock if acquired, or empty if another process holds it
     */
    public Optional<Lock> tryAcquire(String targetArea) {
        Objects.requireNonNull(targetArea, "targetArea cannot be null");

        if (inMemory) {
            Semaphore sem = localLocks.computeIfAbsent(targetArea, k -> new Semaphore(1));
            if (sem.tryAcquire()) {
                AtomicBoolean released = new AtomicBoolean(false);
                return Optional.of(() -> {
                    if (released.compareAndSet(false, true)) {
                        sem.release();
                    }
                });
            }
            return Optional.empty();
        }

        long key = hashTargetArea(targetArea);
        log.debug("Attempting to acquire PostgreSQL advisory lock for targetArea='{}' (key={})", targetArea, key);

        try {
            Connection conn = dataSource.getConnection();
            boolean acquired;
            try (PreparedStatement stmt = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                stmt.setLong(1, key);
                try (ResultSet rs = stmt.executeQuery()) {
                    acquired = rs.next() && rs.getBoolean(1);
                }
            }

            if (acquired) {
                log.info("Successfully acquired non-blocking PostgreSQL advisory lock for targetArea='{}' (key={})", targetArea, key);
                return Optional.of(new PostgresLockHandle(conn, key, targetArea));
            } else {
                conn.close();
                log.debug("Could not acquire advisory lock for targetArea='{}'; currently held by another session", targetArea);
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error during non-blocking advisory lock attempt for targetArea: " + targetArea, e);
        }
    }

    private static class PostgresLockHandle implements Lock {
        private final Connection connection;
        private final long key;
        private final String targetArea;
        private final AtomicBoolean released = new AtomicBoolean(false);

        PostgresLockHandle(Connection connection, long key, String targetArea) {
            this.connection = connection;
            this.key = key;
            this.targetArea = targetArea;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                try (PreparedStatement stmt = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    stmt.setLong(1, key);
                    stmt.execute();
                    log.info("Released PostgreSQL advisory lock for targetArea='{}' (key={})", targetArea, key);
                } catch (SQLException e) {
                    log.error("Failed to release PostgreSQL advisory lock for key {}", key, e);
                } finally {
                    try {
                        connection.close();
                    } catch (SQLException e) {
                        log.warn("Failed to close connection after releasing advisory lock", e);
                    }
                }
            }
        }
    }
}
