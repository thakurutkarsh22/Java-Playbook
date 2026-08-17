# Rate Limiter — Interview Q&A Cheat Sheet

Companion to `Readme.md`. The Readme explains the *design*; this file
captures the questions an SDE-3 interviewer will actually ask.

---

## Q1. Walk me through the token-bucket algorithm with numbers.

**TL;DR** — Capacity = 10, refill = 5/sec. Bucket starts full. Each
`tryAcquire` first refills lazily based on elapsed time, then tries to
take 1 token.

### Worked example

```text
t=0.000s    tokens=10.0
t=0.001s    r1.tryAcquire -> elapsed=1ms -> +0.005 tokens -> 10.0 (capped),
                                          -> 9.0 (after consume)   -> TRUE
t=0.001s    r2.tryAcquire -> 8.0                                    -> TRUE
            ...
t=0.001s    r10.tryAcquire -> 0.0                                   -> TRUE
t=0.001s    r11.tryAcquire -> 0.0 (nothing to refill) -> 0.0       -> FALSE

t=0.201s    r12.tryAcquire -> elapsed=200ms -> +1.0 tokens
                                            -> 1.0 -> 0.0          -> TRUE
t=0.201s    r13.tryAcquire -> 0.0                                  -> FALSE

t=2.001s    r14.tryAcquire -> elapsed=1.8s -> +9.0 tokens -> 9.0,
                                            -> 8.0                 -> TRUE
```

### Why nano-tokens internally?

Fractional refills must not be lost. At 5 tokens/sec and a 1 ms tryAcquire,
the refill is 0.005 tokens. Integer arithmetic would round to 0 and the
bucket would never refill. Nano-tokens preserve the fraction.

---

## Q2. Why is the hot path lock-free, but ConnectionPool's isn't?

**TL;DR** — Different work, different time horizons. CAS is right when the
critical section is **3 instructions** (compare, subtract, store).
ReentrantLock is right when the critical section is **3 milliseconds**
(network I/O).

### Cost comparison

| Op | Cost | What fits |
|---|---|---|
| `AtomicLong.compareAndSet` | 10-20 ns uncontended; 50-100 ns contended | Counter increment, single-word state machine |
| `ReentrantLock.lock` | 20-30 ns uncontended; µs-to-ms contended | Multi-step compound action |
| `Semaphore.acquire` | Same as ReentrantLock; with wait queue | Bounded resource pool |

The token-bucket has exactly one "step" (subtract 1 if non-zero), so CAS is
perfect. The connection pool has multi-step compound actions (decide grow
vs reuse, allocate, hand out), so a lock is right.

### Rule of thumb

> If the entire critical section is `read-modify-write` on a single word,
> use CAS. Otherwise use a lock.

---

## Q3. Walk me through the CAS-on-timestamp trick in `refill()`.

**TL;DR** — Two threads compute `elapsed` simultaneously. Only one wins the
CAS to advance `lastRefillNanos`. The loser sees the new timestamp and
skips its add, so we never double-credit elapsed time.

### The race we're avoiding

```text
t=10ns        lastRefillNanos = 1_000_000_000

Thread A and B both call refill() at t=2_000_000_000:

  A: last = 1_000_000_000
  A: elapsed = 1_000_000_000  (1 second)
  A: toAdd = 5 tokens

  B: last = 1_000_000_000     (same read!)
  B: elapsed = 1_000_000_000
  B: toAdd = 5 tokens
```

Without the CAS, both would credit 5 tokens for the same elapsed second.
The bucket would be 10 over its rightful budget.

### The fix

```java
if (!lastRefillNanos.compareAndSet(last, now)) {
    return;   // someone else just advanced the clock; their add covers us
}
```

The CAS makes "I am the thread responsible for this elapsed window" a
mutually exclusive role.

### Subtle bonus

Notice that the *token count* itself is still updated in a normal CAS loop
*after* we win the timestamp CAS. So we still tolerate concurrent consumers
draining tokens between our refill computation and our refill apply — that
just means the token count can temporarily exceed `capacity` for a few
nanoseconds, which we re-cap in the loop.

Actually no — let me check. We CAS `nanoTokens` from `current` to
`min(current + toAdd, capacityNanos)`. So we never write a value above
capacity. Good.

---

## Q4. Why is `SlidingWindowRateLimiter` *not* lock-free?

**TL;DR** — Because the operation is "drop expired + check size + append" —
three steps that must be atomic. With unsafe concurrent operations, two
threads can both see `size = N-1` and both append.

### The bad code (lock-free attempt)

```java
ConcurrentLinkedDeque<Long> ts = ...;
AtomicInteger count = ...;

while (!ts.isEmpty() && ts.peekFirst() <= cutoff) {
    ts.pollFirst();
    count.decrementAndGet();
}
if (count.get() >= limit) return false;
ts.addLast(now);
count.incrementAndGet();
return true;
```

Race:

```text
Thread A: prune -> count=9 (limit=10)
Thread B: prune -> count=9
Thread A: count.get() = 9 < 10, addLast, count=10
Thread B: count.get() = 9 < 10 (read before A's increment!), addLast, count=11
```

We are now at 11 in the window — bug.

### The fix

A per-key lock makes the three steps atomic:

```java
s.lock.lock();
try {
    prune();
    if (s.timestamps.size() >= limit) return false;
    s.timestamps.addLast(now);
    return true;
} finally { s.lock.unlock(); }
```

### Could we make this lock-free?

Yes, with a more elaborate structure — e.g. a fixed-size ring buffer per
key where each slot is an `AtomicLong` timestamp, and we CAS-slot-by-slot
in order. But that's a different design, way more code, and only worth it
if the lock is the actual bottleneck (which it almost never is for ~µs
critical sections).

---

## Q5. Token-bucket vs sliding-window vs fixed-window — when to use which?

**TL;DR** — Token-bucket for most APIs. Sliding-window when "strict ≤ N in
any window" matters. Fixed-window almost never (boundary burst bug).

### Fixed-window's bug

Window = 1 minute, limit = 100.

```text
t=00:00:59.999 → user fires 100 requests → all OK, counter=100
t=00:01:00.000 → window flips, counter=0
t=00:01:00.001 → user fires 100 more     → all OK, counter=100
```

In 2 ms the user got 200 requests through. For payment APIs or auth
endpoints this is a real attack surface.

### Comparison table (slightly more detailed than Readme)

| Spec phrasing | Best fit |
|---|---|
| "5 requests per second" | TokenBucket (capacity=5, refill=5/s) |
| "Allow short bursts but average 5/s" | TokenBucket (capacity=20, refill=5/s) |
| "At most 10 in any 60-second window" | SlidingWindow |
| "Reset at minute boundaries" | FixedWindow — but accept the boundary-burst risk explicitly |
| "Smooth, no burst" | TokenBucket (capacity=1, refill=N/s) — degenerates to "wait 1/N seconds between requests" |

---

## Q6. How would you distribute this across N nodes?

**TL;DR** — Move the counter to Redis. The algorithm doesn't change; the
state location does.

### Token-bucket on Redis (Lua)

```
-- KEYS: rate_limit:user:123
-- ARGV: now_ms, capacity, refill_per_sec
local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(bucket[1]) or tonumber(ARGV[2])  -- capacity if new
local ts     = tonumber(bucket[2]) or tonumber(ARGV[1])
local elapsed = tonumber(ARGV[1]) - ts
tokens = math.min(tonumber(ARGV[2]), tokens + elapsed * tonumber(ARGV[3]) / 1000)
if tokens < 1 then
    redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', ARGV[1])
    return 0
end
tokens = tokens - 1
redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', ARGV[1])
redis.call('PEXPIRE', KEYS[1], 60000)
return 1
```

Lua runs atomically on a single Redis shard. No locks required because
single-threaded execution.

### Sliding-window on Redis (ZSET)

```
ZREMRANGEBYSCORE rl:{key} 0 (now - window)
ZADD rl:{key} now now
EXPIRE rl:{key} window
ZCOUNT rl:{key} -inf +inf
```

Compare against limit. Done atomically with a Lua script or pipeline.

### What about network latency?

A Redis round-trip is ~1 ms in the same DC. For 100k QPS limiters, batch
or use local pre-check + global periodic sync. Envoy's RLS protocol
batches requests for exactly this reason.

---

## Q7. How do you bound memory for unbounded keyspaces (IP-based)?

**TL;DR** — Idle eviction: drop buckets that are *fully refilled* (no
pending quota state to preserve).

### The trick is the eviction criterion

A fully refilled bucket has zero quota state — dropping it and recreating
it later are bit-for-bit identical. So we can safely evict.

A partially drained bucket has quota state: "this user has consumed 3 of
10 tokens". Dropping it would grant them a fresh 10 immediately. Don't
evict it.

### Why is "fully refilled" easy to test?

```java
buckets.entrySet().removeIf(e ->
    e.getValue().availableTokens() >= e.getValue().capacity());
```

`availableTokens()` runs the lazy refill, so an idle bucket "ages up to
full" the next time we look at it. Eviction is then trivial.

### What if all keys are perpetually active?

Then bounded memory isn't possible *without* changing semantics. Two
options:

1. Use **probabilistic counting** (count-min sketch). Bounded memory,
   bounded error.
2. **Tier the limiter**: high-volume keys use a token bucket; low-volume
   keys share one global "bystander bucket" with looser semantics.

---

## Q8. What's the failure mode if the JVM clock jumps backward?

**TL;DR** — Token-bucket: `elapsed = now - last` goes negative, my code
correctly skips the refill (`if (elapsed <= 0) return`). So no double-refill,
but also no progress until the clock catches up.

### NTP clock jumps

Real machines do step their clock. Old code (`System.currentTimeMillis`)
can move backward; modern guidance is `System.nanoTime()` for elapsed-time
math because it's monotonic on a given JVM.

This code uses `System.nanoTime()` everywhere — by spec, monotonic across
threads on the same JVM (not necessarily across JVMs, but we don't care).

### Sliding-window uses currentTimeMillis — why?

Because we want absolute wall-clock for "in the last 60 seconds of
real-world time". If wall-clock jumps:

- **Forward**: old timestamps age out faster than expected — same as a slow
  caller; benign.
- **Backward**: timestamps are *ahead* of `now`, the deque never prunes.
  Caller is rate-limited as if their old requests are still pending.
  Benign-ish, recovers as wall-clock advances.

For a *strict* limiter (payments) you'd want both — `nanoTime` for the
elapsed math, `currentTimeMillis` only for human-readable logs. Out of
scope for this LLD.

---

## Q9. How do you implement weighted requests (`tryAcquire(int weight)`)?

**TL;DR** — Token-bucket: subtract `weight * 1e9` nano-tokens instead of
`1e9`. Sliding-window: append the weight, sum the weights.

### Token-bucket variant

```java
public boolean tryAcquire(int weight) {
    refill();
    for (;;) {
        long current = nanoTokens.get();
        long cost = (long) weight * 1_000_000_000L;
        if (current < cost) return false;
        if (nanoTokens.compareAndSet(current, current - cost)) return true;
    }
}
```

Use case: a search query that scans 100 rows costs 100 tokens; a simple
GET costs 1.

### Sliding-window variant

```java
record Entry(long ts, int weight) {}
Deque<Entry> entries;
int totalWeight; // maintained incrementally

if (totalWeight + weight > limit) return false;
entries.addLast(new Entry(now, weight));
totalWeight += weight;
```

Pruning subtracts weight when an entry ages out.

### What this teaches

The algorithms generalize cleanly because the underlying primitive (a
*budget*) doesn't care about request *count* — it cares about consumed
*units*. Multi-unit is just a different scale.

---

## Q10. What's the worst-case latency a `tryAcquire` can have under contention?

**TL;DR** — TokenBucket: O(threads concurrently calling) CAS retries,
each ~100 ns. Bounded by physical core count. Sliding-window: 1 lock
acquisition per concurrent caller for the same key.

### TokenBucket CAS retries

```java
for (;;) {
    long current = nanoTokens.get();
    ...
    if (nanoTokens.compareAndSet(current, current - ONE)) return true;
}
```

Under heavy contention every CAS may fail once. With N hardware threads
hitting the same bucket, the wait is bounded by ~N retries × 100 ns. For
N = 64, ~6 µs worst case. For most real workloads (1-2 contending threads
per key) it's ~100-300 ns.

### Sliding-window lock

ReentrantLock under contention queues the waiters. For N threads on the
same key, the Nth waits N × (critical section duration) ≈ N × 1 µs.
Acceptable up to ~10 contending threads per key. Beyond that, switch to
token-bucket or shard the key.

### Mitigation if it does matter

- Shard the key: `String shardedKey = key + ":" + threadId % 8` — gives 8
  independent buckets, multiplies effective rate by 8 but trades off
  fairness across threads.
- For monitoring use only (not enforcement), use `LongAdder` — designed
  for high-contention counting.

---

## Bonus — quickfire round

| Question | Answer (≤2 lines) |
|---|---|
| Why `AtomicLong` and not `LongAdder`? | LongAdder is great for high-contention counters but doesn't support CAS — it splits the counter into per-thread cells. We need single-value semantics. |
| Why does the demo accept `allowed >= 10 && <= 12`? | Between the `start.countDown()` and the first `tryAcquire`, the OS may take ~1-2 ms scheduling threads. At 5 tokens/sec, that's ≤1 extra token of refill. Strict equality would be flaky. |
| Can `tryAcquire` ever return true when it shouldn't? | No. The CAS ensures the decrement is atomic. If two threads see `tokens=1`, one CAS succeeds, the other fails and retries, sees `tokens=0`, returns false. |
| Why daemon thread for the evictor? | So a forgotten `shutdown()` doesn't pin the JVM. The evictor has no durable work. |
| When is `synchronized` ok in a rate limiter? | Never in the hot path. Acceptable for the configuration-update path (changing limit/rate at runtime) where contention is zero. |
| What's the JDK class closest to this design? | `Guava RateLimiter` (Stopwatch-based token-bucket; permits-per-second). Different impl details, same algorithm. |
| How would you A/B-test a new limiter? | Run both limiters in parallel; one is enforcing, the other is shadow-counting. Emit metrics on disagreements. Flip when shadow matches enforcement. |
