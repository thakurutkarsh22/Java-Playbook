package LLD.LldQuestions.RateLimiter.service;

/*
 * The minimum-viable rate-limiter contract.
 *
 *   tryAcquire(key) -> true if the request fits inside the budget for `key`
 *                       at the current instant; false otherwise.
 *
 * Why is `key` parameterized?
 *   - Real systems rate-limit per-user, per-API, per-tenant, per-IP. The key
 *     IS the bucket identifier; the limiter is sharded by it internally.
 *
 * Why no `acquire()` blocking variant?
 *   - Limiters are usually "decide-and-return"; the caller is the one that
 *     decides whether to retry, queue, or 429. Mixing in a blocking variant
 *     temptingly pushes back-pressure inside the limiter, which is the wrong
 *     place -- it should propagate to the caller's queue.
 */
public interface RateLimiter {
    boolean tryAcquire(String key);

    /*
     * Permit count remaining in the current window. Used for the `X-RateLimit-
     * Remaining` response header. Returns -1 if not applicable.
     */
    long remaining(String key);
}
