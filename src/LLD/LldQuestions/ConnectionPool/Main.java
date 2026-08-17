package LLD.LldQuestions.ConnectionPool;

import LLD.LldQuestions.ConnectionPool.exceptions.AcquireTimeoutException;
import LLD.LldQuestions.ConnectionPool.exceptions.PoolClosedException;
import LLD.LldQuestions.ConnectionPool.models.Connection;
import LLD.LldQuestions.ConnectionPool.models.PoolConfig;
import LLD.LldQuestions.ConnectionPool.service.ConnectionPool;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * Demo entry point.
 *
 *   Scenario 1: Bounded admission. 20 callers race for a pool of size 5;
 *               exactly 5 must hold a connection at any instant. The other
 *               15 must either wait their turn or timeout.
 *
 *   Scenario 2: Acquire timeout. With every connection borrowed and held
 *               longer than `acquireTimeout`, a fresh caller must hit
 *               AcquireTimeoutException -- not block forever.
 *
 *   Scenario 3: Idle eviction. After a quiet period the pool prunes back
 *               to minSize. We don't evict below the floor.
 *
 *   Scenario 4: Graceful shutdown. After shutdown(), new acquire() calls
 *               fast-fail with PoolClosedException.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        PoolConfig config = new PoolConfig(
                /*minSize=*/         2,
                /*maxSize=*/         5,
                /*acquireTimeout=*/  Duration.ofMillis(200),
                /*idleTimeout=*/     Duration.ofMillis(300),
                /*evictionInterval=*/Duration.ofMillis(100));

        ConnectionPool pool = new ConnectionPool(config);

        runBoundedAdmission(pool);
        runAcquireTimeout(pool);
        runIdleEviction(pool);
        runShutdown(pool);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenarios
    // ────────────────────────────────────────────────────────────────────────

    private static void runBoundedAdmission(ConnectionPool pool) throws Exception {
        System.out.println("\n=== Scenario 1: 20 callers race; pool size = 5 ===");

        int callers = 20;
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peakActive = new AtomicInteger();
        AtomicInteger served = new AtomicInteger();
        AtomicInteger timedOut = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        ExecutorService pool2 = Executors.newFixedThreadPool(callers);

        for (int i = 0; i < callers; i++) {
            pool2.submit(() -> {
                Connection c = null;
                try {
                    start.await();
                    c = pool.acquire();
                    int now = active.incrementAndGet();
                    peakActive.accumulateAndGet(now, Math::max);
                    c.execute();                    // 5 ms simulated work
                    served.incrementAndGet();
                } catch (AcquireTimeoutException e) {
                    timedOut.incrementAndGet();
                } catch (Exception ignored) {
                    /* unexpected; ignore for demo */
                } finally {
                    if (c != null) {
                        active.decrementAndGet();
                        pool.release(c);
                    }
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool2.shutdown();

        System.out.println("  served=" + served.get() + " timedOut=" + timedOut.get()
                + " peakConcurrent=" + peakActive.get()
                + " maxAllowed=5");
        if (peakActive.get() > 5) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: peak concurrent > maxSize: " + peakActive.get());
        }
    }

    private static void runAcquireTimeout(ConnectionPool pool) throws Exception {
        System.out.println("\n=== Scenario 2: acquireTimeout when every connection is held ===");

        Connection[] held = new Connection[5];
        for (int i = 0; i < held.length; i++) {
            held[i] = pool.acquire();
        }
        long t0 = System.nanoTime();
        try {
            pool.acquire();
            throw new IllegalStateException("INVARIANT VIOLATED: acquire should have timed out");
        } catch (AcquireTimeoutException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            System.out.println("  acquire(6th) timed out after ~" + ms + "ms (budget=200ms): "
                    + e.getMessage());
        }
        for (Connection c : held) {
            pool.release(c);
        }
    }

    private static void runIdleEviction(ConnectionPool pool) throws Exception {
        System.out.println("\n=== Scenario 3: idle eviction drains down to minSize ===");

        // Force-grow the pool to max by acquiring then releasing 5 connections.
        Connection[] held = new Connection[5];
        for (int i = 0; i < held.length; i++) {
            held[i] = pool.acquire();
        }
        for (Connection c : held) {
            pool.release(c);
        }
        int before = pool.totalSize();
        System.out.println("  before idle: total=" + before + " idle=" + pool.idleSize());

        // Wait long enough for several eviction ticks past idleTimeout.
        Thread.sleep(800);

        int after = pool.totalSize();
        System.out.println("  after idle:  total=" + after + " idle=" + pool.idleSize()
                + "  (minSize=2)");

        if (after > 2 || after < 2) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: expected idle eviction to drop total to minSize=2; got " + after);
        }
    }

    private static void runShutdown(ConnectionPool pool) {
        System.out.println("\n=== Scenario 4: shutdown fast-fails new acquires ===");

        pool.shutdown();
        try {
            pool.acquire();
            throw new IllegalStateException("INVARIANT VIOLATED: acquire after shutdown should throw");
        } catch (PoolClosedException e) {
            System.out.println("  acquire after shutdown rejected (as expected): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unused")
    private static void quietAwait(ExecutorService p) throws InterruptedException {
        p.awaitTermination(2, TimeUnit.SECONDS);
    }
}
