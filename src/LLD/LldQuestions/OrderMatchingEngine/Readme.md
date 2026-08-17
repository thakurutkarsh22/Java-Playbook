# Order Matching Engine — Basic LLD

A minimal, production-shaped Low-Level Design for a **stock-exchange-style
limit-order book** with **price-time priority matching**. This is the
final project in the [LLD concurrency curriculum](../README.md) and is the
**anti-pattern lesson**:

> *"Locks are not always the right answer. When the critical section IS
> the entire operation, single-threaded-per-shard beats locks. Shard by
> symbol; give each symbol its own thread."*

NASDAQ does this. CME does this. Coinbase does this. Our 300-line LLD
does this.

---

## 1. Problem statement

A continuously-trading limit order book receives many BUY and SELL
orders concurrently. The engine must guarantee:

| Invariant | Plain English |
|---|---|
| **Price-time priority** | Higher BUY price wins. Lower SELL price wins. Same price → older order wins. |
| **Maker price execution** | A trade executes at the *resting order's* price, not the incoming order's. |
| **Conservation of shares** | No shares are created or destroyed. `tradedVolume + restingVolume = submittedVolume` per side. |
| **No double-fill** | An order with `remainingQuantity = 0` is removed from the book. |
| **Per-symbol independence** | Heavy traffic on AAPL must not slow down MSFT. |
| **No deadlock** | Even under maximum contention, the engine makes progress. |

---

## 2. Architecture

```
                    ┌──────────────────────────────┐
                    │            Main              │
                    │  (5 demo scenarios)          │
                    └───────────────┬──────────────┘
                                    │
                                    ▼
                    ┌──────────────────────────────┐
                    │       MatchingEngine          │
                    │  routes order by symbol;      │
                    │  lock-FREE (CHM lookup)       │
                    └─────┬─────────────┬───────┬───┘
                          │             │       │
            ┌─────────────┘             │       └─────────┐
            ▼                            ▼                  ▼
   ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐
   │ SymbolMatcher AAPL  │  │ SymbolMatcher MSFT  │  │ SymbolMatcher ...   │
   │ ─ ONE thread       │  │ ─ ONE thread       │  │ ─ ONE thread       │
   │ ─ OrderBook        │  │ ─ OrderBook        │  │ ─ OrderBook        │
   │ ─ tape (CLQ)       │  │ ─ tape (CLQ)       │  │ ─ tape (CLQ)       │
   └─────────────────────┘  └─────────────────────┘  └─────────────────────┘
```

**Three crucial properties:**

1. **One matcher per symbol.** A `SymbolMatcher` owns one `OrderBook` and
   one `SingleThreadExecutor`. All mutation happens on that one thread.

2. **Submit returns a `CompletableFuture<Order>`.** Callers never block
   on the matcher. The futures complete after the matcher thread has
   processed the order.

3. **Zero locks. Zero `synchronized`. Zero `volatile` in the OrderBook.**
   The single-threading guarantee replaces all of them.

---

## 3. Directory layout

```
OrderMatchingEngine/
├── Main.java                          # 5 demo scenarios
├── Readme.md                          # this file
├── Interview-QA.md                    # SDE-3 Q&A
├── models/
│   ├── Side.java                      # BUY | SELL
│   ├── Order.java                     # ★ id + symbol + side + price + qty
│   └── Trade.java                     # immutable trade tape entry
└── service/
    ├── OrderBook.java                 # ★ single-threaded; the engine
    ├── SymbolMatcher.java             # ★ one thread per symbol
    └── MatchingEngine.java            # facade; lock-free CHM router
```

---

## 4. Why single-threaded?

The textbook reflex when you see "shared mutable state" is to add a
lock. That's almost always right — except here.

### The critical section IS the operation

A match looks like this:

```text
while incoming.remaining > 0 and otherSide.best matches:
    take oldest order at best price
    record a trade
    decrement both sides' remaining
    if other order is filled, remove it from the book
if incoming.remaining > 0:
    rest it into the book
```

That's the **entire operation** — and it touches the `OrderBook` from
start to finish. A lock around the operation is held for ≈100% of the
op's duration. So the "with lock" version is exactly equivalent to a
single-threaded version, with extra lock-acquire overhead.

### What single-threading buys you

| Property | With locks | With single-thread-per-symbol |
|---|---|---|
| Throughput | Limited by lock fairness + cache invalidation | Limited by L1/L2 cache; ~1M ops/sec |
| Latency tail | p99 spikes on lock contention | Smooth — no thread ever waits on another |
| Reasoning | "Is this access under the right lock?" | "Only ONE thread touches this. Period." |
| Debugger | Need lock-order analysis | Read the code top to bottom |
| Concurrency bugs | Race conditions, ABA, lost-update | Impossible by construction |

### Why is this "anti-pattern" framing important?

Because every Java book teaches `synchronized` / `ReentrantLock`. For
98% of cases that's right. The 2% where it's wrong — exchanges, game
servers, real-time bidding — is precisely where the *single-thread-per-
shard* idiom dominates. Knowing both makes you a senior engineer.

---

## 5. Concurrency primitives, in the order they matter

### 5a. `ExecutorService` of size 1 per symbol (THE LESSON)

```java
matchExecutor = Executors.newSingleThreadExecutor(r -> { ... });
```

This is the **actor** primitive. All work for the symbol is enqueued as
a `Runnable`; the single matcher thread processes them in FIFO order.

Compare to `ElevatorSystem` (project #7): same shape, different domain.
The elevator is an actor because its state ticks continuously. The
matcher is an actor because each operation can't be split.

### 5b. `ConcurrentHashMap<Symbol, SymbolMatcher>` for routing

```java
matchers.computeIfAbsent(symbol, SymbolMatcher::new);
```

The facade's only synchronization. Racing callers on a brand-new symbol
all get the SAME matcher. Same trick as `TicketBooking#showLocks`.

### 5c. `ConcurrentLinkedQueue<Trade>` for the tape

Trades are published lock-free for downstream consumers (market data
feeds, audit, settlement). The matcher thread is the only writer.
Readers are many. CLQ gives:

- Lock-free push for the writer.
- Lock-free iterator for readers.
- Weakly-consistent iteration (writers can append while readers walk).

### 5d. `AtomicReference<Long> lastTradePriceCents`

Public ticker reads — many subscribers, one writer. Atomic read = no
contention with the matcher thread.

### 5e. `CompletableFuture<Order>` for the submit result

```java
public CompletableFuture<Order> submit(Order order) {
    CompletableFuture<Order> result = new CompletableFuture<>();
    matchExecutor.submit(() -> {
        book.submit(order, sink);
        result.complete(order);
    });
    return result;
}
```

Callers can:

- `.get()` to wait synchronously.
- `.thenAccept(o -> ...)` for async pipelines.
- Ignore the future entirely (fire-and-forget).

### 5f. Inside `OrderBook`: pure data structures, ZERO concurrency primitives

The book uses plain `TreeMap` + plain `ArrayDeque`. **Not** `ConcurrentSkipListMap`. **Not** `ConcurrentLinkedDeque`.

Why? Because **only one thread ever touches it**. The single-threading
guarantee provided by `SymbolMatcher` is what allows us to use the
fast, non-thread-safe variants.

This is the **payoff** for the actor pattern: the data structure is as
simple and as fast as a single-threaded implementation could be.

---

## 6. Price-time priority, in 6 lines

```text
bids = TreeMap<price, Deque<Order>>  sorted DESC  -- highest bid first
asks = TreeMap<price, Deque<Order>>  sorted ASC   -- lowest ask first

at each price level, Deque is FIFO of orders submitted at that price
-> picking the head order at the best price IS price-time priority
```

That's it. The whole exchange-matching trick in 6 lines.

### Why the resting price wins (maker pricing)

When BUY @ $102 hits a SELL @ $100, the trade executes at $100. Why?

- The maker (the resting SELL) **published** their price. They're the
  one taking the price risk by committing to the book.
- The taker (the incoming BUY) said "I'll pay up to $102, but match me
  against the best available". So $100 is exactly what they signed up
  for.

This is **maker-taker pricing**. Almost every exchange runs it.

---

## 7. Demo (`Main.java`) — five scenarios

### Scenario 1 — Simple cross

`SELL @ $100` rests; `BUY @ $102` crosses. Trade executes at $100.

```
=== Scenario 1: SELL @ $100 rests, BUY @ $102 crosses -> trade @ $100 ===
  Trade#1 AAPL 50 @ $100.0 (BUY=2, SELL=1)
```

### Scenario 2 — Price-time priority

Two `SELL @ $300` orders. A `BUY @ $300` matches the **older** one first.

```
=== Scenario 2: same price -> oldest sell fills first ===
  Trade#2 MSFT 10 @ $300.0 (BUY=5, SELL=3)
```

### Scenario 3 — Partial fills walk the ladder

A big `BUY @ $102 × 100` eats three resting SELLs at $99, $100, $101.

```
=== Scenario 3: partial fills walk the book up the ladder ===
  Trade#3 TSLA 30 @ $99.0
  Trade#4 TSLA 50 @ $100.0
  Trade#5 TSLA 20 @ $101.0
```

### Scenario 4 — Cross-symbol parallelism

4 symbols × 2000 orders each = 8000 orders submitted in parallel from
4 threads. Each symbol runs on its own matcher thread.

```
=== Scenario 4: 4 symbols match in PARALLEL on their own threads ===
  submitted 8000 orders across 4 symbols in 212 ms
  total trades = 3200
```

### Scenario 5 — Conservation of shares

1000 random orders on one symbol. We assert:

- `tradedVolume ≤ min(submittedBuys, submittedSells)` (no over-trading)
- `restingBuys = submittedBuys - tradedVolume` (every unmatched share is in the book)
- Symmetric for sells.

```
=== Scenario 5: conservation of shares on random workload ===
  buy submitted=26895  sell submitted=25237  traded volume=21119
  resting buys=5776 (expected 5776)  resting sells=4118 (expected 4118)
```

---

## 8. How to run

```bash
cd bel-20
javac -d /tmp/om-build $(find LLD/LldQuestions/OrderMatchingEngine -name '*.java')
java -cp /tmp/om-build LLD.LldQuestions.OrderMatchingEngine.Main
```

---

## 9. What was deliberately left out

| Skipped | Why fine for basic LLD | What to add for production |
|---|---|---|
| Market orders (no limit) | Limit only | Treat MARKET BUY as "limit at MAX_VALUE"; MARKET SELL as "limit at 0" |
| IOC / FOK / GTC time-in-force | Implicitly GTC | Add `TimeInForce` enum + cancellation timer |
| Cancel / amend | Submit only | A `cancel(orderId)` operation, dispatched through the same matcher thread |
| Market data depth feed | Trade tape only | A second event stream with full order book snapshots + diffs |
| Self-trade prevention | Will match a user's BUY against their own SELL | Add `accountId` to Order; skip self-match per regulatory rule |
| Cross-symbol arbitrage | Single symbol per matcher | Spread orders need 2-symbol coordination (Saga, project #6 lesson) |
| Persistence + crash recovery | All in memory | Append-only log of orders; replay on startup |
| Risk checks | None | Pre-trade risk: position limits, fat-finger detection — runs BEFORE the matcher |
| Auction (open/close) | Continuous trading only | Pre-open auction logic; uncrossing algorithm |
| Order priority types | Plain time priority | Pro-rata, size priority, hidden orders, iceberg orders |
| Fee schedules | None | Tiered, maker/taker; computed off the trade |

---

## 10. Interview-ready one-liner

> The matching engine is **sharded by symbol**: each symbol gets one
> `SingleThreadExecutor` and one `OrderBook`. All mutation runs on the
> symbol's matcher thread, so the OrderBook uses plain `TreeMap` +
> `ArrayDeque` — no locks, no atomics, no volatiles. The facade is
> lock-free via `ConcurrentHashMap.computeIfAbsent`. Trades publish to
> a `ConcurrentLinkedQueue` tape; submitters get a
> `CompletableFuture<Order>` so they never block on the matcher. Different
> symbols match fully in parallel. The lesson: **when contention is total,
> single-threaded-per-shard beats locks** — because a lock held for 100%
> of an op's duration IS single-threading, with extra cost. NASDAQ, CME,
> Coinbase, and this LLD all run this shape.
