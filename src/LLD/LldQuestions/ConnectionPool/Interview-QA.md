# Connection Pool — Interview Q&A Cheat Sheet

Companion to `Readme.md`. The Readme explains the *design*; this file
captures the questions an SDE-3 interviewer will actually ask, with full
reasoning behind each decision.

---

## Q1. Why `Semaphore` instead of a `BlockingQueue<Connection>` alone?

**TL;DR** — Because the pool can be **partially empty but still grow**. A
`BlockingQueue` of pre-created connections handles the "always full" case
only; we need the "create on demand, up to a cap" case.

### Three flavors of pool, compared

| Design | Pros | Cons |
|---|---|---|
| Pre-create N, `BlockingQueue.take()` | Trivial; no growth races | Pay all N handshakes at startup; can't shrink |
| `Semaphore(N)` + `Deque` (this design) | Lazy creation; idle eviction | Slightly more code |
| Synchronized custom (`synchronized` + `wait/notify`) | "Classic" Java book answer | No timeout, no fairness, no `tryAcquire`; a 1995 answer |

The Semaphore-plus-Deque shape mirrors HikariCP, PgBouncer, and nginx's
`keepalive` directive. It's the right shape because:

1. Admission control (permit) is **separate** from storage (deque), so we
   can grow the storage lazily.
2. Both ops have rich, timed, interruptible APIs out of the box.

---

## Q2. Walk me through a concurrent `acquire` from 6 threads on a `maxSize=5` pool.

**TL;DR** — 5 acquire permits and proceed; the 6th waits up to
`acquireTimeout` then throws. No connection is double-handed-out.

### Step by step

```text
t=0          permits=5      idle=[c1, c2]    total=2   (pool pre-warmed to minSize=2)

t=1   t1: tryAcquire(200ms)    permits 5→4
       polls idle -> c2
       returns c2

t=1.01 t2: tryAcquire(200ms)   permits 4→3
       polls idle -> c1
       returns c1

t=1.02 t3: tryAcquire(200ms)   permits 3→2
       polls idle -> null
       enters createLock
         total=2 < max=5
         total=3, return new c3
       leaves createLock

t=1.03 t4 .. permits 2→1    new c4
t=1.04 t5 .. permits 1→0    new c5

t=1.05 t6: tryAcquire(200ms)   no permit, blocks on Semaphore's wait queue.

t=1.205 t6 wakes (timeout) with `got=false` -> AcquireTimeoutException
```

### What goes wrong if we skip `createLock`?

Three threads with permits 2→1, 1→0, 0→-1 (oh wait, semaphore won't go
negative, fine) might all see `total = 2` simultaneously and all create a
connection. We'd briefly have `total = 5` but only `permits = 0`, and the
*next* acquirer sees `total = 5` and refuses to create — which is fine —
but we've allocated more sockets than we wanted for a brief window. The
lock prevents the overshoot.

---

## Q3. Why is `tryAcquire(timeout)` the most important call in the file?

**TL;DR** — Because **the failure mode without it is unbounded queueing**,
and unbounded queueing is the path to every "everything was fine until
suddenly the whole system was on fire" outage.

### Little's Law in three lines

```
L (avg in-flight) = λ (arrival rate) × W (avg wait time)
```

If service rate drops while arrivals stay flat, `W` grows; therefore `L`
grows; therefore memory grows; therefore GC pauses grow; therefore service
rate drops further. Without a wait timeout, this loop has no exit.

### What the timeout buys you

- A **predictable upper bound on tail latency**. Caller knows: "I will
  wait at most 200ms. After that I'm getting an error, not blocked."
- Backpressure that propagates upward. Your HTTP request handler can return
  503 to its caller instead of holding a connection forever.
- Observability. You can graph `acquireTimeoutRate` and *see* the saturation
  approaching, instead of waking up to a brownout.

### What if the caller is willing to wait forever?

They almost never are, even when they think they are. Default to a
timeout. If they really need infinite wait, they can pass `Long.MAX_VALUE`
explicitly — that decision should be loud, not implicit.

---

## Q4. Why does `release()` push to idle BEFORE releasing the permit?

**TL;DR** — To preserve the invariant "**permit available ⇒ idle has at
least one connection** (or we can grow)". Reversed order breaks it for a
small window and creates an obscure race.

### The race in slow motion (wrong order)

```text
Thread A (releasing):                      Thread B (acquiring):
  permits.release()      <- permit visible
                                            permits.tryAcquire() -> ok
                                            idle.pollLast()      -> null !!
  idle.offerFirst(c)     <- visible later
```

Thread B legitimately holds a permit, but `idle` is empty. It enters the
`createNew()` branch, which (correctly!) checks `total < maxSize`. If the
pool was already at max, this throws — even though a connection was about
to be available.

### Correct order

```text
Thread A:                                  Thread B:
  idle.offerFirst(c)     <- visible first
  permits.release()
                                            permits.tryAcquire() -> ok
                                            idle.pollLast()      -> c
```

Thread B always finds a connection.

### Does this happen in practice?

Yes, on every release where ≥1 thread is waiting on the semaphore. The bug
shows up under load and is hard to track because the failure looks like a
spurious "pool exhausted" exception with `total < maxSize`.

---

## Q5. Why LIFO on borrow but FIFO on eviction?

**TL;DR** — LIFO maximizes *cache temperature* per connection; FIFO ensures
the *oldest* (most-likely-stale) idle connection is the one that dies.

### LIFO on borrow (`pollLast`)

The connection you just returned has:
- A live TCP socket with a populated send/receive window.
- A live TLS session.
- A live JDBC server-side prepared statement cache.
- A live OS-side file descriptor in the hot CPU cache.

Borrowing the same connection again hits *all* those caches. Borrowing the
oldest one cold-misses everywhere.

### FIFO on eviction (`pollFirst`)

The oldest idle connection is the one most likely to have:
- Drifted to a stale backend instance (DNS changed, load balancer evicted).
- Been silently dropped by an intermediate firewall.
- Accumulated server-side temp resources (PostgreSQL `idle_in_transaction`).

We close it preemptively. Keeping the newer ones means the next borrow has
a higher chance of reusing a *healthy* connection.

### The deque structure makes both O(1)

`LinkedBlockingDeque.offerFirst` / `pollLast` and `pollFirst` are all
O(1) with no internal queue scan. Switching between LIFO/FIFO is a one-method
call, not a data-structure change.

---

## Q6. Walk me through the `evictIdle` algorithm. Why don't you scan to the end?

**TL;DR** — Because the deque is **approximately ordered** oldest-first by
construction (we `offerFirst` on release, and `release` always touches the
connection). Once we hit a fresh one, all later ones are also fresh.

### The structure invariant

- On release: `touch(); offerFirst(c)` → newest is always at the front.
- So *going from front to back* = newest to oldest? Wait, that's reversed.

OK, let me restate carefully:

- `offerFirst(c)` puts `c` at the head.
- Iterating front-to-back via `iterator()` goes head → tail.
- The head is the **most recently released**, the tail is the **least
  recently released**.

So oldest-first iteration is `descendingIterator()` — but I use the
regular iterator and prune based on `lastUsedAt < cutoff`. The first
"young enough" connection I hit, I `break`.

This **only** works because release ordering preserves the
"head=newest, tail=oldest" property. If a future change breaks that (e.g.
borrowing from the head and returning to the tail), the early-break must
go too.

### Why not just iterate everything?

Because under load, `idle` can be 100+ entries, and `evictIdle` runs every
100 ms. Full scans waste CPU. The break-on-fresh trick keeps the eviction
amortized O(k) where k = number actually evicted.

### Why use `idle.remove(c)` instead of `iterator.remove()`?

Because the deque's iterator is *weakly consistent* — concurrent
modifications may reorder the deque under our feet. `idle.remove(c)` is an
atomic op that handles "another thread already pollLast'd this entry"
gracefully (returns false). We skip those.

---

## Q7. Why FAIR semaphore? You said TicketBooking pays 10-15% for fairness.

**TL;DR** — Because the cost of fairness is **dominated by the cost of the
work being protected**. Here we hold the permit for milliseconds. There we
hold it for microseconds. The relative impact is different.

### The math

| Project | Critical section duration | Fairness cost | Effective overhead |
|---|---|---|---|
| ConnectionPool | ~5 ms (DB query) | ~10-15% on permit acquisition | invisible |
| ParkingLot | ~30 µs (scan + flip) | ~10-15% on permit acquisition | measurable |

The fairness penalty is on the *permit* operation itself, which is a few
hundred ns. When the work itself is 5 ms, that's ≪1%.

### What goes wrong without fairness?

Connection pool starvation. A small set of fast threads keep beating the
semaphore (lock-free path on permit available), while a slow thread that
joins the wait queue once never gets pulled off. Real production bug at
many companies. Fix is `Semaphore(N, true)`.

### When is non-fair right for a connection pool?

When (a) wait queue is rarely non-empty, AND (b) every thread is
homogeneous (no slow path that needs to share the pool). Most real systems
fail both, hence default-to-fair.

---

## Q8. What if a borrower never calls `release()`?

**TL;DR** — Permit leak. `total` stays high, the pool eventually exhausts,
every acquire times out.

### Why no automatic cleanup?

We don't know when a "leaked" connection is truly leaked vs. just held by
a slow request. The pool can't unilaterally yank it back.

### How real pools mitigate

- **Leak threshold timer**: track `borrowedAt` per connection; if held >
  `leakThreshold`, log a warning with the borrower's stack trace.
  Doesn't fix the bug, but makes the bug **findable**.
- **Auto-commit + finally idiom**: pair `acquire` with `try-finally
  release` in a single method. The leak surface area collapses to "did
  the developer write `try { ... } finally { pool.release(c); }`?".
- **JDBC standard**: `try-with-resources` on Connection, so `close()` ⇒
  `release` to the pool. HikariCP wraps the real Connection in a proxy
  whose `close()` routes to `release()`.

### What would you add to *this* code?

A `PoolEntry` wrapper that implements `AutoCloseable`:

```java
class PoolEntry implements AutoCloseable {
    final Connection conn;
    final ConnectionPool pool;
    final long borrowedAtNanos;
    public void close() { pool.release(conn); }
}
```

Callers use:

```java
try (PoolEntry e = pool.acquire()) {
    e.conn.execute();
}
```

Leak-proof by construction.

---

## Q9. How would you implement `validateOnBorrow`?

**TL;DR** — Inside `acquire()`, after popping from `idle`, ping the
connection. If it fails, close it, decrement `total`, and loop to try again
(keeping the same permit).

### Sketch

```java
while (true) {
    Connection c = idle.pollLast();
    if (c == null) {
        return createNew();
    }
    if (c.isClosed() || !c.isValid(validateTimeoutMs)) {
        c.close();
        total.decrementAndGet();
        continue;        // try next idle, OR fall into createNew
    }
    return c;
}
```

### Why a loop?

Because validate-fail means we lose a connection, not just retry it.
Looping covers "the entire idle stack is dead" — common right after a DB
restart.

### What does this cost?

A round-trip per borrow. With a 5 ms RTT to the DB, that doubles your
borrow latency. Two mitigations:

1. **TestOnBorrow only if idle > X ms**: stash `lastValidatedAt` and skip
   the test if the connection is "obviously hot".
2. **TestWhileIdle**: the eviction thread does the test instead, so the
   hot path stays fast.

HikariCP uses option 2 by default (`testWhileIdle`), which is the right
trade-off for latency-sensitive services.

---

## Q10. How does this scale to multiple shards / hosts?

**TL;DR** — One `ConnectionPool` per host. A router maps a request to a
pool. The classes don't change.

### Shape

```java
Map<String, ConnectionPool> poolsByHost = new ConcurrentHashMap<>();

ConnectionPool poolFor(String shardId) {
    return poolsByHost.computeIfAbsent(shardId,
        k -> new ConnectionPool(configFor(k)));
}
```

### What changes

- **Health checks**: per-host probe; on failure, mark the pool "draining"
  and route to a replica. Replace `closed: boolean` with `state: enum
  { OPEN, DRAINING, CLOSED }`.
- **Hedging**: send the same request to two pools if one is slow. The
  pool itself doesn't know; the router does it.
- **Per-host `maxSize`**: hot shards get more permits than cold ones.

### What does *not* change

The internals — Semaphore, Deque, evictor — are correct as-is. Sharding by
key + per-shard pool is the universal scale-out story for stateful
resources.

---

## Bonus — quickfire round

| Question | Answer (≤2 lines) |
|---|---|
| Is `LinkedBlockingDeque` strictly thread-safe? | Yes — both ends use distinct locks (`ReentrantLock` per end), so head and tail operations don't contend. |
| Why daemon thread for the evictor? | So JVM exit isn't held up by it. The evictor has no durable work. |
| Why `volatile boolean closed` instead of `AtomicBoolean`? | One-way switch: only one writer (`shutdown`), many readers (every `acquire`). Volatile gives the happens-before; AtomicBoolean would be slightly more, for zero gain. |
| What's the worst pathological pattern for this pool? | All borrowers slow + tiny `acquireTimeout` → every caller times out → no one wins. Cure: queue-depth-based admission (reject early before even trying). |
| Could you use `CompletableFuture` instead of blocking? | Yes — replace `tryAcquire(timeout)` with a non-blocking `CompletableFuture<Connection>` that completes from a callback in the Semaphore. Reactive pools (R2DBC) do exactly this. |
| When is `synchronized` ok here? | Never. `synchronized` has no `tryAcquire(timeout)`, no fairness, no interruption. `ReentrantLock`/`Semaphore` are strictly more capable. |
| What's the JDK class closest to this design? | `HikariCP.HikariDataSource` for JDBC pools. For arbitrary objects: `commons-pool2.GenericObjectPool`. Both are heavier; ours is the *teaching* version. |
