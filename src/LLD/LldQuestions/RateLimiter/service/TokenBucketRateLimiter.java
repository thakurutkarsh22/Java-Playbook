package LLD.LldQuestions.RateLimiter.service;

import LLD.LldQuestions.RateLimiter.models.TokenBucket;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/*
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║                      TokenBucketRateLimiter                            ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * A per-key TokenBucket. Used to express:
 *   - per-user API quota:   key = userId
 *   - per-IP DDoS protect:  key = remoteIp
 *   - per-endpoint:         key = "GET /api/foo"
 *
 * Concurrency model
 * ─────────────────
 *   - ConcurrentHashMap<key, TokenBucket>. Atomic computeIfAbsent for
 *     bucket creation, so two requests from a brand-new key still see the
 *     SAME bucket.
 *   - TokenBucket internals are lock-free (CAS-only).
 *   - The "service" itself has NO locks.
 *
 * Memory management
 * ─────────────────
 *   - Buckets accumulate for every key seen. For unbounded keyspaces (e.g.
 *     IP-based limiting on a public internet endpoint), this is a leak.
 *   - Mitigation: a daemon cleanup thread evicts buckets that have been
 *     full for `idleEvictMs` (full = "no consumption since last refill" =
 *     idle key).
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private final long capacity;
    private final long refillPerSec;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService idleEvictor;

    public TokenBucketRateLimiter(long capacity, long refillPerSec, long idleEvictMs) {
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
        this.idleEvictor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-evictor");
            t.setDaemon(true);
            return t;
        });
        this.idleEvictor.scheduleAtFixedRate(this::evictIdle,
                idleEvictMs, idleEvictMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean tryAcquire(String key) {
        return bucketFor(key).tryAcquire();
    }

    @Override
    public long remaining(String key) {
        TokenBucket b = buckets.get(key);
        return b == null ? capacity : b.availableTokens();
    }

    public void shutdown() {
        idleEvictor.shutdownNow();
    }

    private TokenBucket bucketFor(String key) {
        return buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, refillPerSec));
    }

    /*
     * Evict buckets that are completely full -- they've had no traffic in a
     * while AND can hold no more state. Removing them is safe because the
     * next request for the key will recreate one at full capacity, which
     * is identical to the state we discarded.
     *
     * Note: we deliberately do NOT evict buckets that are partially drained
     * even if they've been idle. Why? Because a partially drained bucket
     * mid-quota would forget how much quota the user has used, effectively
     * doubling their budget on the next request. The "full = safe to drop"
     * rule preserves the budget invariant.
     */
    private void evictIdle() {
        buckets.entrySet().removeIf(e -> e.getValue().availableTokens() >= e.getValue().capacity());
    }
}
