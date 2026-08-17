package LLD.LldQuestions.RateLimiter.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/*
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║                     SlidingWindowRateLimiter                           ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * Contract:
 *   "At most N requests per `windowMillis` for a given key, with the window
 *   sliding forward in real time."
 *
 * Why a different limiter than TokenBucket?
 *   - Token-bucket allows BURSTS up to `capacity`. A user can fire `capacity`
 *     requests in 1 ms and they all succeed. For some APIs (e.g. payment
 *     gateways) this is unacceptable -- they want a strict "no more than N
 *     in any sliding 60s window".
 *
 * Algorithm: keep a deque of request timestamps per key.
 *   - tryAcquire: drop timestamps older than `now - windowMillis`. If the
 *     deque size is < limit, append `now` and return true. Else return false.
 *   - Naturally enforces sliding-window semantics: the deque IS the window.
 *
 * Why a lock and not lock-free?
 *   - The check-and-append is two ops: size() and addLast(). With an unsafe
 *     deque, two threads could see size = N-1 and both append, exceeding N.
 *   - A non-blocking deque (ConcurrentLinkedDeque) plus an AtomicInteger
 *     counter is possible but tricky -- the order of the size check and the
 *     append matters. A ReentrantLock makes correctness obvious.
 *
 *   - Lock granularity: per key. So different users / IPs are independent.
 *     A hot key still serializes its own bucket, but the bucket op is ~µs.
 *
 * Trade-offs vs token-bucket
 * ──────────────────────────
 *   ✗ Memory: O(limit) per key (vs O(1) for token bucket).
 *   ✗ Not lock-free.
 *   ✓ Strict sliding-window guarantee (no burst above limit in any window).
 *   ✓ Easy to query "how many requests in the last 60s?" (deque size).
 *
 * Verdict for SDE-3 interviews: name BOTH algorithms, explain the trade,
 * default to token-bucket unless the spec says "strict sliding window".
 */
public class SlidingWindowRateLimiter implements RateLimiter {

    private final int limit;
    private final long windowMillis;

    /*
     * Per-key state:
     *   - timestamps: deque of request times (epoch ms)
     *   - lock:       fairness control over the check-and-append
     *
     * Both wrapped in a tiny holder so we own them as a pair under one CHM
     * lookup. Splitting into two CHMs is correct but doubles the lookups.
     */
    private static final class WindowState {
        final Deque<Long> timestamps = new ArrayDeque<>();
        final ReentrantLock lock = new ReentrantLock();
    }

    private final ConcurrentHashMap<String, WindowState> states = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(int limit, long windowMillis) {
        if (limit <= 0 || windowMillis <= 0) {
            throw new IllegalArgumentException(
                    "limit and windowMillis must be > 0; got " + limit + "/" + windowMillis);
        }
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    @Override
    public boolean tryAcquire(String key) {
        WindowState s = states.computeIfAbsent(key, k -> new WindowState());
        s.lock.lock();
        try {
            long now = System.currentTimeMillis();
            long cutoff = now - windowMillis;
            // Evict timestamps that have aged out. ArrayDeque is FIFO -- the
            // head is oldest -- so this is an O(k) prune of expired entries.
            while (!s.timestamps.isEmpty() && s.timestamps.peekFirst() <= cutoff) {
                s.timestamps.pollFirst();
            }
            if (s.timestamps.size() >= limit) {
                return false;
            }
            s.timestamps.addLast(now);
            return true;
        } finally {
            s.lock.unlock();
        }
    }

    @Override
    public long remaining(String key) {
        WindowState s = states.get(key);
        if (s == null) {
            return limit;
        }
        s.lock.lock();
        try {
            long cutoff = System.currentTimeMillis() - windowMillis;
            while (!s.timestamps.isEmpty() && s.timestamps.peekFirst() <= cutoff) {
                s.timestamps.pollFirst();
            }
            return limit - s.timestamps.size();
        } finally {
            s.lock.unlock();
        }
    }
}
