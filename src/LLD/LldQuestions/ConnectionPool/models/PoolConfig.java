package LLD.LldQuestions.ConnectionPool.models;

import java.time.Duration;

/*
 * All knobs for the pool, gathered in one immutable record-like object so it
 * is trivially safe to share across threads.
 *
 *   minSize           - the floor on `total` size. We refuse to evict below
 *                       this even if connections idle out. Reason: cold-start
 *                       latency. A new TCP+TLS handshake is 50-200 ms; we
 *                       always keep a few warm.
 *
 *   maxSize           - the cap on `total`. Acquire-when-full waits up to
 *                       acquireTimeout, then throws.
 *
 *   acquireTimeout    - the longest a caller may wait for a connection.
 *                       Beyond this we fast-fail so upstream callers don't
 *                       pile up (Little's Law: queue length = arrival × wait).
 *
 *   idleTimeout       - if a pooled connection has not been used for this
 *                       long, the eviction thread closes it. Trims the pool
 *                       back toward `minSize` during quiet periods.
 *
 *   evictionInterval  - how often the eviction thread runs.
 *
 * Defaults below mirror what you'd see in HikariCP / pgbouncer for a
 * mid-sized service.
 */
public class PoolConfig {

    private final int minSize;
    private final int maxSize;
    private final Duration acquireTimeout;
    private final Duration idleTimeout;
    private final Duration evictionInterval;

    public PoolConfig(int minSize,
                      int maxSize,
                      Duration acquireTimeout,
                      Duration idleTimeout,
                      Duration evictionInterval) {
        if (minSize < 0 || maxSize < minSize) {
            throw new IllegalArgumentException(
                    "Require 0 <= minSize <= maxSize; got " + minSize + "/" + maxSize);
        }
        this.minSize = minSize;
        this.maxSize = maxSize;
        this.acquireTimeout = acquireTimeout;
        this.idleTimeout = idleTimeout;
        this.evictionInterval = evictionInterval;
    }

    public int getMinSize() {
        return minSize;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public Duration getAcquireTimeout() {
        return acquireTimeout;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public Duration getEvictionInterval() {
        return evictionInterval;
    }
}
