package LLD.LldQuestions.ConnectionPool.models;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/*
 * A pretend database/network connection. In a real pool this would wrap a
 * java.sql.Connection or a Netty Channel; here it is just enough state to
 * simulate (a) "doing work" and (b) "being broken".
 *
 * Concurrency notes
 * ─────────────────
 * The pool itself is the synchronization point -- a Connection is either
 * idle in the pool OR checked out to exactly one caller, never both. So
 * within the pool we treat the connection as single-threaded.
 *
 * `closed` is `volatile` because the eviction thread may close a connection
 * AFTER the user has already returned it but BEFORE another user borrows it,
 * and the next borrower needs to see the closed bit immediately.
 */
public class Connection {

    private static final AtomicLong ID = new AtomicLong(0);

    private final long id;
    private final Instant createdAt;
    private volatile Instant lastUsedAt;
    private volatile boolean closed;

    public Connection() {
        this.id = ID.incrementAndGet();
        this.createdAt = Instant.now();
        this.lastUsedAt = this.createdAt;
    }

    public long getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void touch() {
        this.lastUsedAt = Instant.now();
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        this.closed = true;
    }

    /*
     * Simulate work done over the connection -- 5 ms by default. Real impls
     * would issue a SQL statement and block on the wire.
     */
    public void execute() throws InterruptedException {
        if (closed) {
            throw new IllegalStateException("Connection " + id + " is closed");
        }
        Thread.sleep(5);
        touch();
    }

    @Override
    public String toString() {
        return "Conn#" + id;
    }
}
