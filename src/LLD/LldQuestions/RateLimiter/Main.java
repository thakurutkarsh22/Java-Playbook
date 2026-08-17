package LLD.LldQuestions.RateLimiter;

import LLD.LldQuestions.RateLimiter.service.RateLimiter;
import LLD.LldQuestions.RateLimiter.service.SlidingWindowRateLimiter;
import LLD.LldQuestions.RateLimiter.service.TokenBucketRateLimiter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * Demo entry point.
 *
 *   Scenario 1: Token-bucket burst. Bucket capacity=10, refill=5/sec. 50
 *               threads race; the first 10 must succeed (burst); the next
 *               40 mostly fail in the very next instant, then start succeeding
 *               at ~5/sec.
 *
 *   Scenario 2: Token-bucket isolation. Two keys (user A, user B) share the
 *               limiter; draining A's bucket must NOT affect B's budget.
 *
 *   Scenario 3: Sliding-window strict bound. 50 threads race; with limit=10
 *               per window=1s, EXACTLY 10 must succeed in the first window,
 *               regardless of how fast the threads arrive.
 *
 *   Scenario 4: Sliding-window slide. Wait 1s after Scenario 3, fire 10 more
 *               requests, all must succeed.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        runTokenBucketBurst();
        runTokenBucketKeyIsolation();
        runSlidingWindowBound();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenarios
    // ────────────────────────────────────────────────────────────────────────

    private static void runTokenBucketBurst() throws Exception {
        System.out.println("\n=== Scenario 1: TokenBucket burst (capacity=10, refill=5/sec) ===");

        TokenBucketRateLimiter limiter =
                new TokenBucketRateLimiter(/*capacity=*/10, /*refillPerSec=*/5, /*idleEvictMs=*/60_000);

        int contestants = 50;
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(contestants);
        ExecutorService pool = Executors.newFixedThreadPool(contestants);

        for (int i = 0; i < contestants; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (limiter.tryAcquire("user-A")) {
                        allowed.incrementAndGet();
                    } else {
                        denied.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        System.out.println("  burst:  allowed=" + allowed.get() + " denied=" + denied.get()
                + "  (expected allowed ~= 10)");
        // Allow a tiny slack: between the start.countDown and the first tryAcquire,
        // the lazy refill may add ~1 token if the schedulers' clock moved.
        if (allowed.get() < 10 || allowed.get() > 12) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: token-bucket burst allowed " + allowed.get()
                            + ", expected ~10");
        }

        // After 1 second of idle, ~5 more tokens should have refilled.
        Thread.sleep(1100);
        int after = 0;
        for (int i = 0; i < 20; i++) {
            if (limiter.tryAcquire("user-A")) after++;
        }
        System.out.println("  after 1s refill: 20 retries -> " + after + " allowed"
                + "  (expected ~5)");
        if (after < 4 || after > 7) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: refill expected ~5, got " + after);
        }
        limiter.shutdown();
    }

    private static void runTokenBucketKeyIsolation() {
        System.out.println("\n=== Scenario 2: TokenBucket key isolation ===");

        TokenBucketRateLimiter limiter =
                new TokenBucketRateLimiter(10, 5, 60_000);

        // Drain user-A completely.
        int drained = 0;
        while (limiter.tryAcquire("user-A")) {
            drained++;
        }
        System.out.println("  user-A drained=" + drained + " (expected 10)");

        // user-B should still have full budget.
        int bGranted = 0;
        for (int i = 0; i < 20; i++) {
            if (limiter.tryAcquire("user-B")) bGranted++;
        }
        System.out.println("  user-B after draining A: granted=" + bGranted + " (expected 10)");
        if (bGranted != 10) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: per-key isolation, user-B got " + bGranted);
        }
        limiter.shutdown();
    }

    private static void runSlidingWindowBound() throws Exception {
        System.out.println("\n=== Scenario 3: SlidingWindow strict (limit=10 per 1s) ===");

        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(10, 1000);

        int contestants = 50;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(contestants);
        ExecutorService pool = Executors.newFixedThreadPool(contestants);

        for (int i = 0; i < contestants; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (limiter.tryAcquire("api-x")) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        System.out.println("  sliding: allowed=" + allowed.get() + " denied="
                + (contestants - allowed.get()) + "  (expected exactly 10)");
        if (allowed.get() != 10) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: sliding-window strict bound, got " + allowed.get());
        }

        // Slide: wait a full window, retry; must allow another 10.
        Thread.sleep(1100);
        int secondWindow = 0;
        for (int i = 0; i < 20; i++) {
            if (limiter.tryAcquire("api-x")) secondWindow++;
        }
        System.out.println("  after 1s slide: allowed=" + secondWindow + " (expected 10)");
        if (secondWindow != 10) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: window slide allowed " + secondWindow);
        }
    }

    @SuppressWarnings("unused")
    private static void quiet(ExecutorService p) throws InterruptedException {
        p.awaitTermination(2, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unused")
    private static RateLimiter genericLimiter() {
        // Keep the import path warm; this would be the interface-typed wiring.
        return new TokenBucketRateLimiter(10, 5, 60_000);
    }
}
