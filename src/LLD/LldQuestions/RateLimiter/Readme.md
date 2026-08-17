# Rate Limiter — Basic LLD

Two **production-shaped** rate-limiter implementations sharing one
`RateLimiter` interface:

1. **Token Bucket** (lock-free, allows burst)
2. **Sliding Window** (lock-per-key, strict)

This is **project #3** in the [LLD concurrency curriculum](../README.md). It
is the canonical worked example for the pattern:

> *"Lock-free CAS on the hot path; ScheduledExecutorService only for the
> cold path (eviction). Pick the algorithm whose accuracy/cost trade-off
> matches the spec — not the one you happen to know."*

---

## 1. Problem statement

A public API receives traffic from many users / IPs / tenants. We must
guarantee:

| Invariant | Plain English |
|---|---|
| **Per-key budget** | A user cannot exceed N requests per second. |
| **Per-key isolation** | One user exhausting their budget must not affect another. |
| **No central bottleneck** | The limiter itself cannot become the slowest thing in the request path. |
| **Burst control (TokenBucket) or strictness (SlidingWindow)** | Choose the right algorithm for the SLA. |
| **Bounded memory** | Idle keys are reclaimed; the map cannot grow without bound. |

---

## 2. Two algorithms, one interface

```
                              ┌───────────────────────┐
                              │     RateLimiter       │
                              │ tryAcquire(key)       │
                              │ remaining(key)        │
                              └─────────┬─────────────┘
                                        │
                  ┌─────────────────────┴─────────────────────┐
                  ▼                                            ▼
   ┌───────────────────────────┐                ┌───────────────────────────┐
   │ TokenBucketRateLimiter    │                │ SlidingWindowRateLimiter  │
   │ (lock-free CAS)           │                │ (per-key ReentrantLock)   │
   │ allows bursts up to cap.  │                │ strict ≤ N in any window  │
   │ steady-state = refillPS   │                │ memory = O(limit) per key │
   └───────────────────────────┘                └───────────────────────────┘
```

Both implement the same interface so the caller is decoupled from the
algorithm choice — change the bean wiring, not the call site.

---

## 3. Directory layout

```
RateLimiter/
├── Main.java                        # 3 concurrent demo scenarios
├── Readme.md                        # this file
├── Interview-QA.md                  # SDE-3 Q&A
├── models/
│   └── TokenBucket.java             # ★ lock-free atomic-token engine
└── service/
    ├── RateLimiter.java             # the interface
    ├── TokenBucketRateLimiter.java  # per-key TokenBucket + idle evictor
    └── SlidingWindowRateLimiter.java# per-key deque-of-timestamps + lock
```

---

## 4. Concurrency primitives, in the order they matter

### 4a. `AtomicLong` for the token count (TokenBucket)

The hot path is one CAS:

```java
for (;;) {
    long current = nanoTokens.get();
    if (current < ONE_TOKEN) return false;
    if (nanoTokens.compareAndSet(current, current - ONE_TOKEN)) return true;
}
```

No mutex, no kernel call. ~10–20 ns per uncontended acquire; ~50–100 ns
under modest contention.

**Why `AtomicLong` and not `AtomicInteger`?** Because we store tokens in
nano-token units (token × 1e9) so we can carry fractional refill between
calls. With integer tokens, a 0.4-token refill is lost; with nano-tokens it
accumulates correctly.

### 4b. Lazy refill on every `tryAcquire`

Instead of a `ScheduledExecutorService` ticking every X ms (which would have
resolution-vs-cost trade-offs), the bucket refills *on demand*:

```java
long now = System.nanoTime();
long elapsed = now - lastRefillNanos.get();
if (lastRefillNanos.compareAndSet(last, now)) {
    addTokens(elapsed * refillPerSec);
}
```

The CAS on `lastRefillNanos` is what guarantees we never double-add the
same time slice across racing threads. Whichever thread wins the CAS gets
to "advance the clock" by `elapsed`; the others see a fresh `lastRefillNanos`
and skip the add.

### 4c. `ConcurrentHashMap.computeIfAbsent` for the per-key bucket

```java
buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, refillPerSec));
```

`computeIfAbsent` is atomic — even if 1000 threads hit a brand-new key in
the same nanosecond, they all see the **same** bucket. This is the same
"shard by key" trick we use in `TicketBooking` and `ParkingLot`.

### 4d. `ScheduledExecutorService` *only* for idle eviction

The scheduled task is **not** part of the hot path. It runs every
`idleEvictMs` (default 60 s) and removes buckets that are fully refilled
(i.e., have had no consumption since they last filled up). This is the
cold-path cleanup.

**Why is "fully refilled" the right eviction criterion?** Because a
partially drained bucket carries quota state. Evicting it would let the
user fire `capacity` fresh requests immediately (the recreated bucket is
full), effectively granting double quota. A *full* bucket has no quota
state to preserve — dropping it is a no-op.

### 4e. Per-key `ReentrantLock` for SlidingWindow

```java
WindowState s = states.computeIfAbsent(key, k -> new WindowState());
s.lock.lock();
try { /* prune, check, append */ } finally { s.lock.unlock(); }
```

Why is a lock required here when TokenBucket is lock-free? Because the op
is **two steps**: prune-old-timestamps + check-size + append-now. A
lock-free deque (`ConcurrentLinkedDeque`) plus an `AtomicInteger` counter
could work, but the order of size-check and append is racy — two threads
seeing `size = N-1` would both append, exceeding N.

A small per-key lock makes correctness obvious. Granularity is per-key, so
different users don't contend. The lock is held for ~µs.

---

## 5. Algorithm comparison

The most important table in this file.

| Property | TokenBucket | SlidingWindow |
|---|---|---|
| **Memory per key** | O(1) — two longs | O(limit) — one timestamp per recent req |
| **Hot path** | Lock-free CAS | Per-key `ReentrantLock` |
| **Burst behavior** | Up to `capacity` tokens at once | None — strict ≤ N in any window |
| **Refill / aging** | Continuous (fractional tokens) | Discrete (drop timestamps past cutoff) |
| **Common use** | API gateways, internal RPC | Auth login, payment retries, SMS sends |
| **`X-RateLimit-Remaining` header** | Accurate but "soft" (refilling) | Exact integer (limit - deque.size) |
| **Distributed variant** | Redis Lua `INCRBY` + `EXPIRE` | Redis `ZSET` of timestamps + `ZCOUNT` |
| **Adversarial worst case** | User sends `capacity` then steady at `rate` | User sends `N`, waits *just* under window, sends `N` again — effectively `2N` per window across the boundary |

The "fixed-window" variant (often shown alongside) is **not** here because
its boundary-burst bug (2× in 1 ms at a window boundary) makes it
genuinely worse than token-bucket for almost any real use case.

---

## 6. Demo (`Main.java`) — three scenarios

### Scenario 1 — TokenBucket burst + refill

Bucket capacity=10, refill=5/sec. 50 threads race; ~10 must succeed (burst);
~40 must fail. Then after 1 second of idle, ~5 more tokens are available.

```
=== Scenario 1: TokenBucket burst (capacity=10, refill=5/sec) ===
  burst:  allowed=10 denied=40  (expected allowed ~= 10)
  after 1s refill: 20 retries -> 5 allowed  (expected ~5)
```

### Scenario 2 — TokenBucket per-key isolation

Drain user-A's bucket; user-B's must still be full.

```
=== Scenario 2: TokenBucket key isolation ===
  user-A drained=10 (expected 10)
  user-B after draining A: granted=10 (expected 10)
```

### Scenario 3 — SlidingWindow strict bound + slide

limit=10 per 1 s; 50 threads race; **exactly** 10 must succeed in the first
window. After 1 s slide, another 10 must succeed.

```
=== Scenario 3: SlidingWindow strict (limit=10 per 1s) ===
  sliding: allowed=10 denied=40  (expected exactly 10)
  after 1s slide: allowed=10 (expected 10)
```

---

## 7. How to run

```bash
cd bel-20
javac -d /tmp/rl-build $(find LLD/LldQuestions/RateLimiter -name '*.java')
java -cp /tmp/rl-build LLD.LldQuestions.RateLimiter.Main
```

---

## 8. What was deliberately left out

| Skipped | Why fine for basic LLD | What to add for production |
|---|---|---|
| Distributed limiter | Single JVM | Redis Lua script: `INCRBY rl:{key} 1 EX 60`, reject if return > limit |
| Cost-weighted requests | `tryAcquire(1)` only | `tryAcquire(int weight)` — token-bucket trivially supports this; sliding-window needs `addLast(weight)` and sum |
| Reject vs queue policy | Reject always | Add a `Strategy`: reject, queue with timeout, fail-open, fail-closed |
| Multiple tiers | Single limiter | Composite: global limiter > per-tenant > per-key (call all in series) |
| Adaptive rate | Static limits | Token-bucket with `refillPerSec` updated by a controller (PID loop on tail latency) |
| Metrics | None | Prometheus counters: `rate_limited_total{key=...}`; histogram of denial rate |
| Cluster-aware budget | Per-instance limits | "Token budget" service that all instances borrow from (e.g. Envoy's rate-limit service) |

---

## 9. Interview-ready one-liner

> Token-bucket gives lock-free admission with bursts: each key holds an
> `AtomicLong` of nano-tokens that refills lazily on every `tryAcquire`,
> with a `compareAndSet` on the timestamp guaranteeing we never double-add
> elapsed time across racers. Sliding-window gives strict per-key bounds at
> the cost of a tiny per-key `ReentrantLock` and O(limit) memory.
> `ConcurrentHashMap.computeIfAbsent` is the per-key shard primitive; a
> daemon `ScheduledExecutorService` evicts fully-refilled buckets so memory
> stays bounded against unbounded keyspaces.
