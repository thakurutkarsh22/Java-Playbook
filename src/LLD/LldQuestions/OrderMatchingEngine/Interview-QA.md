# Order Matching Engine — Interview Q&A Cheat Sheet

Companion to `Readme.md`. The Readme explains the *design*; this file
captures the SDE-3-level questions an interviewer will actually ask. The
headline lesson is the **anti-pattern**: single-threaded-per-shard
beats locks when contention is total.

---

## Q1. Why single-threaded? Won't locks scale better?

**TL;DR** — No. The matching operation **IS** the critical section.
A lock around it is held for ~100% of the op's duration. The "with
locks" version is therefore identical to single-threaded, plus the
overhead of locking.

### Walk through the math

A typical match touches:

- `bids.firstKey()` — 1 read
- `asks.firstKey()` — 1 read
- `level.peekFirst()` — 1 read
- `incoming.fill(qty)` — 1 write
- `resting.fill(qty)` — 1 write
- `tape.offer(trade)` — 1 write
- `level.pollFirst()` (sometimes) — 1 write
- `ladder.remove(price)` (sometimes) — 1 write
- repeat the inner loop until filled — variable

Every line is a read or write of book state. There is no "I/O" portion,
no "I can release the lock now" gap. The whole match is one tight,
unbreakable critical section.

### What if you split with finer locks?

You could lock per *price level*. Now two matches at different price
levels run in parallel. But:

- A match almost always *spans* multiple levels (walks the ladder).
- You'd need to acquire locks across levels in order to prevent
  deadlock — same lesson as `AtmTransfer` (project #6).
- The overhead of N lock acquisitions per match dominates the gain.

### The actual numbers

A single-threaded matcher on modern hardware runs ~1–10M matches/sec.
The CPU is bottlenecked on L1/L2 cache misses for the book traversal,
not on logic. **Adding locks slows this down by 2-4×.**

A real exchange runs ~5k symbols. 5k threads × 1M ops/sec each = 5B
ops/sec aggregate. NASDAQ peaks at ~10M trades/sec. The math fits.

### When IS multi-threading per shard right?

When the operations within a shard can be made **independent**. For a
matcher, no — every operation can affect any subsequent one. For a
read-mostly cache, yes — you can have N readers in parallel.

---

## Q2. Walk me through one match with price-time priority.

**TL;DR** — Walk the opposing side from best price, FIFO within each
level, decrementing both sides as you go.

### State before

```text
Symbol: AAPL
asks (low->high):  $100 × [#A1(50), #A2(30)]    -- A1 older
                   $101 × [#A3(20)]
bids (high->low):  $98  × [#B1(10)]
```

### Incoming: BUY @ $101 × 80

```text
inc.remaining = 80
Loop:
  asks.firstKey() = $100. $100 <= $101. Match.
    level = [A1, A2]
    resting = A1. qty = min(80, 50) = 50.
    A1.fill(50) -> A1.remaining = 0. Trade @ $100 × 50.
    A1 is filled, level.pollFirst() removes A1.
    inc.remaining = 30.
  level = [A2]. Inner loop:
    resting = A2. qty = min(30, 30) = 30.
    A2.fill(30) -> 0. Trade @ $100 × 30.
    A2 polled. level empty -> remove $100 from asks.
    inc.remaining = 0.
  loop ends (inc filled)
```

Two trades produced: `[Trade(50 @ $100), Trade(30 @ $100)]`.
The book is now:

```text
asks: $101 × [#A3(20)]
bids: $98 × [#B1(10)]
```

The incoming `BUY × 80` is fully consumed; no resting bid added.

### What if the incoming were BUY @ $101 × 200?

After eating $100 (80 shares total), the next level is $101 ≤ $101.
Match again:

```text
resting = A3. qty = min(120, 20) = 20.
A3.fill(20) -> 0. Trade @ $101 × 20.
inc.remaining = 100.
level/asks now empty above the incoming's limit.
loop ends (no matchable asks).
Rest the remaining 100 shares as a bid at $101.
```

Final state:

```text
asks: (empty)
bids: $101 × [incoming(100)]
      $98 × [#B1(10)]
```

---

## Q3. Why does the trade execute at the MAKER's price?

**TL;DR** — Because the maker committed to that price by resting it on
the book. The taker said "I'll pay up to X" — taking the better price
is exactly what they wanted.

### Two ways the trade could price

| Convention | Example | Trade price |
|---|---|---|
| **Maker pricing** ← used here | SELL @ $100 rests, BUY @ $102 arrives | $100 |
| Mid pricing | Same | $101 |
| Taker pricing | Same | $102 |

### Why maker pricing wins

- **Fair to the maker**: they're providing liquidity. They published
  their price; the market rewards them with the price they asked for.
- **Fair to the taker**: they expressed "willing to pay up to $102".
  Getting filled at $100 is a *win* for them.
- **Industry standard**: NYSE, NASDAQ, LSE, BSE, NSE all do maker pricing.

### What about mid pricing?

Common in **dark pools** and OTC venues — the bid-ask spread is split
50/50 between maker and taker. Different incentive structure (rewards
takers more, which encourages liquidity-taking). Out of scope here.

---

## Q4. Why `TreeMap` + `ArrayDeque`, not `ConcurrentSkipListMap` + `ConcurrentLinkedDeque`?

**TL;DR** — Because the matcher is single-threaded by construction. The
concurrent variants would just add overhead for synchronization that's
already implied.

### Performance comparison

| Op | `TreeMap` | `ConcurrentSkipListMap` |
|---|---|---|
| `firstKey()` | O(log n) tree walk | O(log n) + atomic ops |
| `put` | O(log n) | O(log n) + CAS retries |
| Memory per entry | ~32 bytes | ~64 bytes |
| Cache friendliness | Excellent (red-black tree compact) | Worse (skip-list pointers) |

For our use case, *TreeMap is strictly faster* — we get the
performance benefits because we don't need the concurrency benefits.

### When would I switch?

If the OrderBook ever became multi-threaded. There's no scenario in this
design where that would be right (see Q1).

### What about benchmarks?

A `TreeMap.firstKey()` on a small map (5-20 entries — typical book
depth) is ~10 ns. CSL `firstKey()` is ~50 ns due to volatile reads. For
1M ops/sec, that's 40 ms vs 200 ns: a 4× throughput delta.

---

## Q5. How does this scale to 10k symbols?

**TL;DR** — One thread per symbol gets expensive. Mitigate with
**virtual threads** (JEP 444) or by **multiplexing** several cold
symbols onto one thread.

### Thread cost in plain JDK

- Each Java thread = ~1 MB of stack memory.
- 10k symbols × 1 MB = 10 GB. Real machines don't have that.
- Plus context-switch overhead.

### Two real options

#### Option A: Virtual threads (Java 21+)

```java
Executors.newVirtualThreadPerTaskExecutor()
```

Virtual threads cost ~few KB each. 10k of them is 50 MB total. JVM
manages the scheduling on ~CPU-count carrier threads. **This is the
right answer for modern JDK.**

#### Option B: Multiplex cold symbols

```java
class CarrierMatcher {
    ExecutorService thread = newSingleThreadExecutor();
    Map<String, OrderBook> books;
    public void submit(Order o) {
        thread.submit(() -> books.get(o.getSymbol()).submit(...));
    }
}
```

Hot symbols (top 100) get their own carrier. Cold symbols (everything
else) share a few carriers. This is how some legacy exchanges run
on JDK 8/11.

### Doesn't multiplexing break the "no contention" rule?

No — within a CarrierMatcher's thread, ops still serialize. The thread
just round-robins over multiple symbols. There's still ONE thread per
symbol's state, it's just shared.

---

## Q6. What's the worst-case latency for a `submit` call?

**TL;DR** — Queue depth × per-match cost. With ~1M ops/sec per matcher,
a queue of 1000 pending = 1 ms tail.

### What dominates

```text
submit() steps:
  1. CHM lookup (10 ns)
  2. matchExecutor.submit() = enqueue Runnable (50 ns)
  3. return future (5 ns)
  4. matcher thread eventually picks it up (queue wait)
  5. matcher runs the match (~1 µs)
  6. completes the future
```

Steps 1-3 are tens of nanoseconds. Step 4 is the variable: it depends on
how many pending Runnables are ahead.

### Bounding the queue

```java
matchExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<>(1000),
    rejectedExecutionHandler);
```

If the queue fills, we reject with `RejectedExecutionException` — same
pattern as `ConnectionPool.acquireTimeoutException`. Caller gets a
clear "exchange is overloaded, retry later".

### Why not unbounded?

Same reason as everywhere else: Little's Law. `latency = queue_depth /
throughput`. If throughput drops (GC, system call, anything), queue
grows; latency grows; users hate you. Cap and reject.

---

## Q7. How would you implement order cancellation?

**TL;DR** — Send a `CancelOrder` message through the same matcher
thread. It walks the book and removes by id.

### Sketch

```java
public CompletableFuture<Boolean> cancel(long orderId, String symbol) {
    CompletableFuture<Boolean> result = new CompletableFuture<>();
    matcher(symbol).submit(() -> {
        Order found = book.findById(orderId);
        if (found == null) { result.complete(false); return; }
        book.remove(found);
        result.complete(true);
    });
    return result;
}
```

### Why through the matcher thread?

So you don't have to worry about racing with a concurrent match. If
match #1 is in progress when cancel arrives, the cancel runs
*after* match #1 (FIFO order in the matcher's queue). The order may
have been fully filled by match #1, in which case cancel returns
`false` — exactly the right semantic.

### What about racing with the *submission*?

```text
Thread A: submit(order #99)        -- queued
Thread B: cancel(order #99)        -- queued AFTER A
matcher thread:
  runs submit(99)
  runs cancel(99) -> finds 99, cancels
```

Both ops serialize. Cancel only sees order #99 *after* it's been
submitted. Correct.

```text
Thread B: cancel(order #99)        -- queued first (order doesn't exist yet)
Thread A: submit(order #99)        -- queued after
matcher thread:
  runs cancel(99) -> not found, returns false
  runs submit(99) -> rests order
```

Caller of `cancel` sees `false` and knows the cancellation didn't take
effect. They can either retry or accept that the order is now live.

---

## Q8. How do you persist for crash recovery?

**TL;DR** — Append-only event log of all submissions (and cancels). On
restart, replay deterministically.

### The journal

```
0001 SUBMIT order#1 AAPL BUY @100 × 50
0002 SUBMIT order#2 AAPL SELL @100 × 30
0003 [matcher emitted Trade(1, 2, $100, 30)]
0004 SUBMIT order#3 AAPL BUY @101 × 20
0005 CANCEL order#1
```

Two kinds of events:
- **Inputs** (from clients): SUBMIT, CANCEL
- **Outputs** (from matcher): Trade

Only inputs need to be persisted *before* applying. Outputs are
*derived* — replaying inputs reproduces outputs deterministically.

### Replay

On startup:
```text
for each input event in order:
    apply to a fresh OrderBook (single-threaded)
    discard the recomputed outputs (or compare to journaled outputs as sanity)
```

After replay, the book is back to the pre-crash state. Now resume normal
operation.

### Why does this work even with multi-symbol parallelism?

Because each symbol's events are *independently* journaled. Different
symbols don't interact, so we can replay per symbol independently.

### What about half-written events?

The append-only log uses CRC + length-prefixed records. A torn write
fails CRC; recovery truncates the partial record. Standard
write-ahead-log technique used by Kafka, RocksDB, etc.

---

## Q9. Compare this with `ElevatorSystem`. Both are actors — what's different?

**TL;DR** — Same primitive (one thread per shard), different *trigger*.
The elevator self-ticks; the matcher only runs when a message arrives.

### Why the elevator ticks

The elevator has *continuous internal state*: position, direction.
Without external input it still needs to advance one floor per tick.
Hence `scheduleAtFixedRate`.

### Why the matcher doesn't tick

The order book has *no autonomous state change*. It only changes when an
order arrives. Hence the matcher thread is idle until something is
queued, then it processes, then it goes idle again. `SingleThreadExecutor`
gives exactly this: a thread that wakes when a Runnable is enqueued.

### Which is more efficient?

Matcher-style. Idle = zero CPU. Elevator-style burns ~1% CPU per car
even when nothing happens, because of the tick.

### When do you pick which?

- **Continuous state**: actor with scheduled tick. (Game world,
  simulation, control system.)
- **Message-driven state**: actor with SingleThreadExecutor + queue.
  (Matching engine, in-memory cache, event sourcing.)

Both share the lock-free guarantee. The difference is just whether you
need a heartbeat.

---

## Q10. How would you test this for correctness under load?

**TL;DR** — Property-based test with conservation-of-shares invariant.
Plus a deterministic single-thread test for the matching algorithm.

### Property test

```text
Given:
  - N random orders for one symbol (BUY/SELL, random price, random qty)
Run:
  - submit all, wait for futures
Assert:
  - traded volume <= min(submitted BUYs, submitted SELLs)
  - resting BUYs + traded volume = submitted BUYs
  - resting SELLs + traded volume = submitted SELLs
  - every trade.price is between the maker's bid and the taker's bid
  - every trade.buyOrder.limit >= trade.price >= trade.sellOrder.limit
```

Scenario 5 in our `Main.java` implements the first three. The price
invariants are obvious additions.

### Determinism

To make the test reproducible:
- Seed the RNG: `new Random(42)`.
- Submit serially in scenario 5 (not from multiple threads).
- Use `future.get()` to ensure each match completes before the next
  submit.

### JCStress for memory-model edges

Run a `@JCStressTest` where two actors submit one BUY and one SELL,
asserting a single trade in any order. Should be uneventful (the
matcher serializes anyway) — JCStress *verifying* it's uneventful is
the value.

### Load test

```text
For symbols S = 1, 4, 16, 64, 256:
    for QPS Q = 10k, 100k, 1M:
        run the engine for 60s, measure p50/p99/p999 latency
```

Expected curve: throughput scales linearly with S until S exceeds CPU
core count. p99 stays low if queues are bounded; tail grows when
queues saturate.

---

## Bonus — quickfire round

| Question | Answer (≤2 lines) |
|---|---|
| Why `AtomicLong NEXT_ID` for Order.id? | Cheap, lock-free, monotonic. Suffices as the time-priority key — newer ids = later submission. |
| Why `Instant timestamp` on Trade? | Audit. Match-time != receive-time, but for the LLD they're the same. |
| Why `ConcurrentLinkedQueue` for the tape, not a blocking queue? | Many readers; we never want a slow reader to block the writer. CLQ writers are non-blocking. |
| What if two BUYs at the same price race? | They serialize through the matcher thread. Whichever submit() returned the future first goes first. |
| Why CompletableFuture instead of an `onTraded` callback? | Composability — `thenAccept`, `thenCombine`, `allOf`. Callbacks compose poorly. |
| Can a self-trade happen? | Yes — same trader's BUY and SELL match. Real exchanges have self-trade-prevention rules (skip the match, leave both resting). Out of scope here. |
| What's the JDK class closest to this design? | Akka actors, or the Disruptor pattern. In pure JDK: `SingleThreadExecutor` + `LinkedBlockingQueue` is the canonical actor. |
| What if the matcher thread dies? | `SingleThreadExecutor` will spin up a replacement on the next submit. State is preserved because the new thread sees the OrderBook through the same reference. But — any in-flight work is lost. Mitigate by writing your `Runnable` to throw on critical paths so the future fails loudly. |
