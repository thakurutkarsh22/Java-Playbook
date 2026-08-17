package LLD.LldQuestions.RateLimiter.models;

import java.util.concurrent.atomic.AtomicLong;

/*
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║                            TokenBucket                                 ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 *   capacity      - maximum tokens the bucket can hold ("burst budget")
 *   refillPerSec  - tokens added per second ("steady-state rate")
 *   tokens        - current count (always in [0, capacity])
 *
 * Mental model
 * ────────────
 *   - Imagine a bucket of size 10, refilling at 5 tokens/sec. At rest it
 *     holds 10. A burst of 10 requests in 50 ms all succeed (drains the
 *     bucket). After that, requests succeed at 5/sec (refill rate). The
 *     bucket recovers to 10 tokens after 2s of idle.
 *
 * Why TWO knobs?
 *   - `capacity` controls BURST. Set high to allow short spikes.
 *   - `refillPerSec` controls SUSTAINED rate. Set low to enforce a quota.
 *   - Independent knobs let you say: "burst up to 100, but only 10/sec
 *     average" -- a common shape for public APIs.
 *
 * Lock-free design
 * ────────────────
 *   - tokens is an AtomicLong. The hot path is a single compareAndSet():
 *
 *       long current = tokens.get();
 *       if (current < 1) return false;
 *       if (!tokens.compareAndSet(current, current - 1)) retry;
 *       return true;
 *
 *     No mutex, no blocking, no kernel calls. Designed for ~10M ops/sec
 *     on a single core.
 *
 *   - Refill is done lazily on each tryAcquire: compute "tokens to add since
 *     last touch", add them up to capacity. This means even if the scheduled
 *     refill thread is busy, we never give out fewer tokens than we should.
 *
 *   - lastRefillNanos is the read-modify-write of "last time we computed
 *     refill". Also a CAS to keep it monotonic across racers.
 *
 * Why not just a ScheduledExecutorService that adds tokens every tick?
 *   - Two problems with the naive "schedule a refill task every 200 ms":
 *     (a) Resolution: caller's tryAcquire at t=99ms sees 0 tokens even though
 *         half a refill is due.
 *     (b) Drift: if the scheduler is delayed (GC pause), we never make up
 *         the lost time.
 *   - Lazy refill on read is both more precise and more robust to scheduler
 *     hiccups. The scheduled task in RateLimiter is only used to PRINT
 *     metrics; the bucket itself is self-refilling.
 */
public final class TokenBucket {

    private final long capacityNanos;       // capacity * 1e9 (precomputed)
    private final long refillPerSec;
    private final long capacity;

    // tokens are stored × 1e9 (nano-tokens) so we can add fractional tokens
    // accumulated between calls. tokens.get() / 1e9 = whole tokens.
    private final AtomicLong nanoTokens;
    private final AtomicLong lastRefillNanos;

    public TokenBucket(long capacity, long refillPerSec) {
        if (capacity <= 0 || refillPerSec <= 0) {
            throw new IllegalArgumentException(
                    "capacity and refillPerSec must be > 0; got " + capacity + "/" + refillPerSec);
        }
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
        this.capacityNanos = capacity * 1_000_000_000L;
        this.nanoTokens = new AtomicLong(capacityNanos);            // start full
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    /*
     * Try to consume 1 token. Returns true on success, false if the bucket
     * is empty. Thread-safe and lock-free.
     *
     * Algorithm:
     *   1. Refill lazily based on elapsed time since lastRefillNanos.
     *   2. CAS-loop to decrement by 1 token (1e9 nano-tokens).
     */
    public boolean tryAcquire() {
        refill();
        for (;;) {
            long current = nanoTokens.get();
            if (current < 1_000_000_000L) {                          // < 1 token
                return false;
            }
            if (nanoTokens.compareAndSet(current, current - 1_000_000_000L)) {
                return true;
            }
            // CAS failed: another thread updated; retry
        }
    }

    /*
     * Lazy refill: compute the elapsed time since the last refill and add
     * that many tokens (capped at capacity).
     *
     * The CAS on `lastRefillNanos` ensures that even if N threads enter
     * concurrently, exactly ONE wins the right to advance the timestamp, so
     * we don't double-add the same elapsed time.
     */
    private void refill() {
        long now = System.nanoTime();
        long last = lastRefillNanos.get();
        long elapsed = now - last;
        if (elapsed <= 0) {
            return;                                                  // clock didn't advance
        }
        if (!lastRefillNanos.compareAndSet(last, now)) {
            // someone else just refilled -- their CAS will have added the
            // tokens for the elapsed window already; we can safely skip.
            return;
        }
        // Tokens to add = elapsed_seconds × refillPerSec = (elapsed * refillPerSec) tokens
        // In nano-tokens: tokens × 1e9 = elapsed * refillPerSec (since elapsed is in ns
        // and refillPerSec is per second, the unit math works out).
        long toAdd = elapsed * refillPerSec;

        // Atomic add-with-cap: standard CAS loop.
        for (;;) {
            long current = nanoTokens.get();
            long next = current + toAdd;
            if (next > capacityNanos) {
                next = capacityNanos;
            }
            if (nanoTokens.compareAndSet(current, next)) {
                return;
            }
        }
    }

    public long availableTokens() {
        refill();
        return nanoTokens.get() / 1_000_000_000L;
    }

    public long capacity() {
        return capacity;
    }

    public long refillPerSec() {
        return refillPerSec;
    }
}
