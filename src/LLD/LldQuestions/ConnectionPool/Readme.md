# Connection Pool — Basic LLD

A minimal, production-shaped Low-Level Design of a database / network
**connection pool** (HikariCP / pgbouncer / aiohttp.TCPConnector shape),
focused on the **hardest** part: **bounded admission with bounded wait**,
plus idle eviction that respects a floor.

This is **project #2** in the [LLD concurrency curriculum](../README.md). It
is the canonical worked example for the pattern:

> *"Use a `Semaphore` to bound admission; use `tryAcquire(timeout)` to bound
> wait; use a single-threaded scheduled evictor to bound resource growth."*

---

## 1. Problem statement

Many threads (HTTP request handlers, scheduled jobs) compete for a finite
pool of expensive, slow-to-create resources (TCP+TLS connections). The pool
must guarantee:

| Invariant | Plain English |
|---|---|
| **Bounded resource** | Never more than `maxSize` connections exist at once. |
| **Bounded wait** | A caller waits at most `acquireTimeout` for a connection; then a clear error. |
| **No leak** | Returning a connection always reopens a permit for the next caller — even on exceptions. |
| **Warm floor** | At least `minSize` connections survive the eviction sweep, so cold-start latency is amortized. |
| **Graceful shutdown** | After `shutdown()`, new acquires fail-fast; idle connections close. |

---

## 2. Architecture

```
                        ┌──────────────────────────────┐
                        │            Main              │
                        │  (4 race-condition demos)    │
                        └───────────────┬──────────────┘
                                        │
                                        ▼
                        ┌──────────────────────────────────────┐
                        │           ConnectionPool             │
                        │  ┌─────────────────────────────────┐ │
                        │  │ permits  : Semaphore(max, FAIR) │ │   admission
                        │  │ idle     : LinkedBlockingDeque  │ │   storage
                        │  │ total    : AtomicInteger        │ │   count
                        │  │ createLk : ReentrantLock        │ │   grow-decision
                        │  │ evictor  : Scheduled (daemon)   │ │   prune
                        │  │ closed   : volatile boolean     │ │   one-way switch
                        │  └─────────────────────────────────┘ │
                        └──────────────────────────────────────┘
```

There is no model-vs-service split here: the pool *is* the service. The
`Connection` model is a thin holder for "alive / closed / lastUsedAt".

---

## 3. Directory layout

```
ConnectionPool/
├── Main.java                        # 4 concurrent demo scenarios
├── Readme.md                        # this file
├── Interview-QA.md                  # SDE-3 Q&A
├── models/
│   ├── Connection.java              # id + state + execute() simulator
│   └── PoolConfig.java              # min/max/timeouts/eviction-interval
├── service/
│   └── ConnectionPool.java          # ★ everything interesting
└── exceptions/
    ├── AcquireTimeoutException.java
    └── PoolClosedException.java
```

---

## 4. Concurrency primitives, in the order they matter

### 4a. `Semaphore(maxSize, fair=true)` — admission control

The headline primitive. Holds N permits; `acquire`/`release` drain and
refill. We pass `fair=true` because:

- Connection wait is already ~ms-scale, so the ~10-15% fairness penalty is
  invisible.
- In an unfair pool, a small set of hot threads can monopolize permits and
  the slow threads starve. Fair = FIFO = no starvation.

The Semaphore is **not** the storage; it is purely the *right to take a
connection*. The connections themselves live in `idle`.

### 4b. `tryAcquire(timeout, TimeUnit)` — bounded wait

```java
boolean got = permits.tryAcquire(timeout, MILLISECONDS);
if (!got) throw new AcquireTimeoutException(...);
```

This is the single most important call. **Without a timeout, a hot path with
back-pressure failure piles up indefinitely** — Little's Law guarantees the
queue grows without bound when arrival > service. Fast-failing at the budget
keeps the upstream caller's tail latency predictable.

### 4c. `LinkedBlockingDeque<Connection>` — the storage

Why a deque, not a `BlockingQueue`?

| Op | Why we want it |
|---|---|
| `offerFirst` / `pollLast` | LIFO on borrow = the *hot* connection is the *next* one borrowed. CPU cache + TCP window + TLS session ID are all already warm on it. |
| `pollFirst` / `peekFirst` | FIFO on eviction = the oldest idle connection is the one we close. |

Why thread-safe by construction (no external lock)? `LinkedBlockingDeque`
uses fine-grained internal locks on each operation. We don't need an outer
lock because each individual op is atomic.

### 4d. `AtomicInteger total` — live count

Tracks `idle + checked-out`. Read in O(1) for status displays; incremented
inside `createLock` when growing, decremented on eviction.

Why `AtomicInteger` instead of `total.size()` on a list? Because reads are
many (status endpoints, metrics), writes are few. An atomic counter is
cheaper than locking a list to count it.

### 4e. `ReentrantLock createLock` — serialize grow decisions

The trickiest bit. Why is this lock needed at all? Because the check `if
(total < maxSize) createConnection()` is **two ops, not one**, and many
threads with admission permits could see `total = maxSize - 1` simultaneously
and overshoot.

The fix is to serialize the *decision*, not the connection's life cycle:

```java
createLock.lock();
try {
    if (total.get() < maxSize) {
        total.incrementAndGet();
        return new Connection();
    }
} finally {
    createLock.unlock();
}
```

The lock is held for ~µs (one read, one increment, one allocation).
Throughput penalty is negligible because the *common* case is "an idle
connection exists" and never enters this branch.

### 4f. Daemon `ScheduledExecutorService evictor`

Trims idle connections older than `idleTimeout`, **but never below
`minSize`**. The min floor matters: a cold-start TCP+TLS handshake is
50-200 ms; keeping a few warm saves that latency on the first request after
a quiet period.

```java
if (total.get() - 1 < minSize) {
    idle.offerFirst(c);   // restore, don't evict
    break;
}
```

Daemon thread = does not block JVM exit.

### 4g. The release ordering rule

On `release(c)`:

```java
idle.offerFirst(c);    // make the connection visible FIRST
permits.release();     // THEN signal "a permit is free"
```

Order matters. If we released the permit first, a thread waking on the
permit could `idle.pollLast()` and find nothing — even though we are about
to push it. The invariant "permit ⇒ idle has at least one connection OR
we can grow" is preserved by the offer-then-release order.

(Note: in the *grow* path the same invariant holds differently — we don't
push to idle, we hand the freshly created connection to the caller directly.
Either way, the caller's permit covers exactly one connection's lifetime.)

---

## 5. The four primitives, side by side

| Primitive | Job | If you remove it... |
|---|---|---|
| `Semaphore permits` | admission control | unbounded concurrent connections; resource exhaustion |
| `LinkedBlockingDeque idle` | storage of unused connections | no way to reuse; every request pays handshake cost |
| `AtomicInteger total` | size accounting | no way to know when to grow vs reuse |
| `ReentrantLock createLock` | serialize grow decision | races overshoot `maxSize` by up to N concurrent growers |

Notice no `synchronized` block anywhere. We never want it here because every
one of our wait paths needs a `timeout` variant, which `synchronized` cannot
provide.

---

## 6. Demo (`Main.java`) — four scenarios

### Scenario 1 — Bounded admission

20 callers race; pool size = 5. We assert the *peak concurrent* count never
exceeds 5.

```
=== Scenario 1: 20 callers race; pool size = 5 ===
  served=20 timedOut=0 peakConcurrent=5 maxAllowed=5
```

### Scenario 2 — Acquire timeout

Hold all 5 connections; a 6th caller must fail with `AcquireTimeoutException`
after ~200ms, not block forever.

```
=== Scenario 2: acquireTimeout when every connection is held ===
  acquire(6th) timed out after ~211ms (budget=200ms): no connection available within 200ms
```

### Scenario 3 — Idle eviction respects min floor

Grow pool to maxSize, then let it sit idle. After `idleTimeout +
evictionInterval` we expect `total == minSize`.

```
=== Scenario 3: idle eviction drains down to minSize ===
  before idle: total=5 idle=5
  after idle:  total=2 idle=2  (minSize=2)
```

### Scenario 4 — Graceful shutdown

After `shutdown()`, new acquires fail-fast.

```
=== Scenario 4: shutdown fast-fails new acquires ===
  acquire after shutdown rejected (as expected): pool is closed
```

---

## 7. How to run

```bash
cd bel-20
javac -d /tmp/cp-build $(find LLD/LldQuestions/ConnectionPool -name '*.java')
java -cp /tmp/cp-build LLD.LldQuestions.ConnectionPool.Main
```

---

## 8. What was deliberately left out

| Skipped | Why fine for basic LLD | What to add for production |
|---|---|---|
| Real driver wrap | Demo uses simulated `Connection.execute()` | Wrap `java.sql.Connection` / `Channel`; expose a `Wrapper` interface |
| Validation queries | None — assume connection is good | `connection.isValid(timeout)` before handing out; "test on borrow" |
| Leak detection | None | Track `borrowedAt` per connection; if held > `leakThreshold`, log the borrower's stack |
| Soft-eviction (replace) | Eviction only closes | "Refresh" pattern: evict + create a fresh one in one go for slowly-drifting backend addresses |
| Multiple pools per host | Single pool | Per-host (`Map<String, ConnectionPool>`) for sharded DBs |
| Metrics | None | Wait-time histogram, in-use gauge, create-rate counter (Prometheus) |
| Backpressure on the *server* | None | Server-side `max_connections` complements client-side `maxSize`; mismatched → bad day |

---

## 9. Interview-ready one-liner

> A fair `Semaphore(maxSize)` bounds admission; `tryAcquire(timeout)` bounds
> wait; a `LinkedBlockingDeque` stores idle connections LIFO so the hottest
> one is borrowed first; an `AtomicInteger` + small `ReentrantLock`
> serialize the *grow* decision so concurrent racers don't overshoot
> `maxSize`; a daemon `ScheduledExecutorService` prunes connections idle past
> `idleTimeout` but never below `minSize`. The release ordering rule —
> *push to idle BEFORE releasing the permit* — is what preserves the
> invariant "permit available ⇒ a connection is genuinely takeable."
