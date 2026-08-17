package LLD.LldQuestions.ConnectionPool.service;

import LLD.LldQuestions.ConnectionPool.exceptions.AcquireTimeoutException;
import LLD.LldQuestions.ConnectionPool.exceptions.PoolClosedException;
import LLD.LldQuestions.ConnectionPool.models.Connection;
import LLD.LldQuestions.ConnectionPool.models.PoolConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/*
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║                          ConnectionPool                                ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * Goals (in priority order)
 * ─────────────────────────
 *   1. Bounded resource: never exceed maxSize total connections.
 *   2. Bounded wait:     callers see AcquireTimeoutException after T ms.
 *   3. Liveness:         idle connections evicted down to minSize floor.
 *   4. Graceful shutdown: in-flight callers drain; new callers fail-fast.
 *
 * Primitives, and why each
 * ────────────────────────
 *
 *   permits : Semaphore(maxSize, FAIR)
 *     - admission control. Whoever holds a permit has the right to one
 *       connection. Permits are released back when the user returns it.
 *     - FAIR because connection pools that aren't fair exhibit the
 *       "thread starvation" classic: a small set of hot threads keep
 *       barging in, while one unlucky thread waits forever. The fairness
 *       cost (~10-15%) is paid eagerly here because connection wait is
 *       already milliseconds, not microseconds.
 *
 *   idle    : LinkedBlockingDeque<Connection>
 *     - the actual storage. LIFO on borrow (`pollLast`) so we keep the
 *       hot connection hot (CPU cache + TCP-window-already-open). FIFO
 *       on eviction (`peekFirst`/`pollFirst`) so the OLDEST idle goes first.
 *     - thread-safe by design; we don't need an external lock for the
 *       deque mutations themselves.
 *
 *   total   : AtomicInteger
 *     - count of LIVE connections (idle + checked-out). Never exceeds
 *       maxSize. Read in O(1); incremented inside createLock when growing.
 *
 *   createLock : ReentrantLock
 *     - serializes the "do I need to create a new connection?" decision.
 *       Without it, on cold start N permits would race and try to create
 *       N+ connections, all but one of which would be wasted (or worse,
 *       cause `total` to exceed maxSize transiently).
 *
 *   evictor : ScheduledExecutorService (single daemon)
 *     - prunes connections idle > idleTimeout, but never below minSize.
 *
 *   closed  : volatile boolean
 *     - one-way switch flipped by shutdown(). Read on every acquire to
 *       fail-fast post-shutdown.
 *
 * Acquire flow (the interesting one)
 * ──────────────────────────────────
 *
 *   1. Reject early if closed.
 *   2. tryAcquire(permit, timeout) -- if no permit within budget, throw.
 *   3. We now hold one permit; we MUST either return a connection or
 *      release the permit (otherwise we leak admission tokens).
 *   4. Pop an idle connection. If non-null and not closed, return it.
 *   5. Else (no idle, or idle was poisoned) try to create one inside
 *      createLock. If total < maxSize, increment and create.
 *
 * Release flow
 * ────────────
 *
 *   1. If the connection is broken, close it, decrement total, release
 *      the permit (so a future acquirer can create a fresh one).
 *   2. Else push to idle (offerFirst -> LIFO on next borrow) and release
 *      the permit.
 *
 * Note the *order* of release. We push to `idle` BEFORE releasing the
 * permit. That way, a thread woken by the permit will definitely see the
 * connection in `idle` -- not a race window where it sees the permit and
 * polls an empty deque.
 */
public class ConnectionPool {

    private final PoolConfig config;
    private final Semaphore permits;
    private final LinkedBlockingDeque<Connection> idle = new LinkedBlockingDeque<>();
    private final AtomicInteger total = new AtomicInteger(0);
    private final ReentrantLock createLock = new ReentrantLock();
    private final ScheduledExecutorService evictor;
    private volatile boolean closed = false;

    public ConnectionPool(PoolConfig config) {
        this.config = config;
        this.permits = new Semaphore(config.getMaxSize(), /*fair=*/ true);

        // Pre-warm to minSize so the first N acquires don't pay the create cost.
        for (int i = 0; i < config.getMinSize(); i++) {
            Connection c = new Connection();
            idle.offerFirst(c);
            total.incrementAndGet();
        }

        this.evictor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pool-evictor");
            t.setDaemon(true);
            return t;
        });
        this.evictor.scheduleAtFixedRate(
                this::evictIdle,
                config.getEvictionInterval().toMillis(),
                config.getEvictionInterval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public Connection acquire() throws InterruptedException {
        if (closed) {
            throw new PoolClosedException("pool is closed");
        }

        boolean got = permits.tryAcquire(
                config.getAcquireTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!got) {
            throw new AcquireTimeoutException(
                    "no connection available within " + config.getAcquireTimeout().toMillis() + "ms");
        }

        try {
            // First try to reuse an idle connection. May happen many times if
            // the popped connection turns out to be closed (eviction race).
            Connection c;
            while ((c = idle.pollLast()) != null) {
                if (!c.isClosed()) {
                    return c;
                }
                // Idle but closed: the evictor got it. We still hold our permit
                // and `total` has already been decremented by the evictor, so
                // we are free to fall through and create a fresh one.
            }

            // No idle connection. Create one, but serialize the decision so
            // multiple racers don't overshoot maxSize.
            return createNew();
        } catch (RuntimeException e) {
            // Anything fatal -> return the permit so we don't leak admission.
            permits.release();
            throw e;
        }
    }

    public void release(Connection c) {
        if (c == null) {
            return;
        }
        // Broken connection: discard, free admission for someone else to
        // create a fresh one.
        if (c.isClosed()) {
            total.decrementAndGet();
            permits.release();
            return;
        }
        c.touch();
        // Offer to the FRONT (LIFO) so the hot connection is the next one
        // borrowed. Helps cache locality and keeps the TCP keepalive useful.
        idle.offerFirst(c);
        // Release the permit AFTER offering -- so any thread woken on the
        // permit will see the connection in `idle`.
        permits.release();
    }

    public void shutdown() {
        closed = true;
        evictor.shutdownNow();
        // Close all idle connections. In-flight ones will be closed when
        // their borrowers call release() (because the connection's `close`
        // bit is set via the normal teardown path of a real driver -- we
        // simulate by leaving them; an acquire() after shutdown fast-fails).
        Connection c;
        while ((c = idle.pollFirst()) != null) {
            c.close();
            total.decrementAndGet();
        }
    }

    public int totalSize() {
        return total.get();
    }

    public int idleSize() {
        return idle.size();
    }

    public int inUseSize() {
        return totalSize() - idleSize();
    }

    private Connection createNew() {
        createLock.lock();
        try {
            if (total.get() >= config.getMaxSize()) {
                // We held a permit but somebody else already grew us to max
                // and returned the connection while we were waiting on the
                // lock. Try idle again before giving up.
                Connection c = idle.pollLast();
                if (c != null && !c.isClosed()) {
                    return c;
                }
                // Genuinely no headroom and no idle. Should be rare because
                // permits acted as admission, but possible if `total` has
                // hit the cap from eviction-paranoid creations elsewhere.
                throw new AcquireTimeoutException(
                        "pool internal state desync: max=" + config.getMaxSize()
                                + " total=" + total.get());
            }
            Connection c = new Connection();
            total.incrementAndGet();
            return c;
        } finally {
            createLock.unlock();
        }
    }

    /*
     * Evict idle connections whose lastUsedAt is older than idleTimeout,
     * unless removing them would drop `total` below minSize.
     *
     * Order of operations matters: we test the floor on `total` (which
     * counts idle + checked-out), not on `idle.size()`. Otherwise we'd
     * evict aggressively during a traffic spike where idle is small but
     * total is healthy.
     */
    private void evictIdle() {
        if (closed) {
            return;
        }
        Instant cutoff = Instant.now().minus(config.getIdleTimeout());
        // We iterate from oldest (front) to newest (back). LinkedBlockingDeque's
        // iterator is weakly consistent: safe for traversal under writes.
        Iterator<Connection> it = idle.iterator();
        while (it.hasNext()) {
            Connection c = it.next();
            if (c.getLastUsedAt().isAfter(cutoff)) {
                // Connections are touched on release(), so the deque is
                // mostly ordered oldest-first. Once we hit a fresh one we
                // can stop.
                break;
            }
            // Try to remove via the deque's atomic op so we don't fight with
            // an acquire() that's also polling. If `remove` returns false,
            // the connection is already gone -- skip it.
            if (!idle.remove(c)) {
                continue;
            }
            // Respect the minSize floor.
            int after = total.get() - 1;
            if (after < config.getMinSize()) {
                // Restore -- put it back to the front (still warm).
                idle.offerFirst(c);
                break;
            }
            c.close();
            total.decrementAndGet();
        }
    }

    // ── for tests ────────────────────────────────────────────────────────────
    /*
     * Visible for the demo only. Lets the demo wait for the evictor to run
     * without sleeping for the full eviction interval.
     */
    public Duration getEvictionInterval() {
        return config.getEvictionInterval();
    }
}
