# LLD Questions — Concurrency Curriculum

This folder contains production-shaped Low-Level Designs that exist to teach
**one specific concurrency lesson each**. They are deliberately small enough
to read end-to-end in a sitting and deliberately *not* CRUD apps — every
project's `Main.java` proves an invariant under contention.

Target audience: **SDE-3 / 8-year engineers** preparing for senior-level LLD
rounds at Big-Tech / fintech / consumer-internet shops. Reading order matters
because each project assumes the primitives from the previous one.

---

## TL;DR — read in this order

| # | Project | Primary primitive | Hardest idea |
|---|---|---|---|
| 1 | [`ParkingLot/`](./ParkingLot/) | per-key `ReentrantLock` + `Semaphore` per zone | Counting resource pool; ticket idempotency |
| 2 | [`ConnectionPool/`](./ConnectionPool/) | `Semaphore` + blocking queue + idle eviction | Bounded wait, leak detection, graceful shutdown |
| 3 | [`RateLimiter/`](./RateLimiter/) | `AtomicLong` + `ScheduledExecutorService` | Lock-free CAS hot path; token bucket vs sliding window |
| 4 | ⭐ [`TicketBooking/`](./TicketBooking/) | per-show fair `ReentrantLock` + TTL soft-hold + sweeper | Two-phase commit across a slow external call |
| 5 | [`HotelBooking/`](./HotelBooking/) | per-room `ReentrantLock` + interval-tree overlap test | Range-conflict predicate inside the critical section |
| 6 | [`AtmTransfer/`](./AtmTransfer/) | Ordered two-lock acquisition + idempotency keys | Deadlock avoidance; at-most-once on retries |
| 7 | [`ElevatorSystem/`](./ElevatorSystem/) | One `SingleThreadExecutor` per car + `PriorityBlockingQueue` | Multi-actor coordination; SCAN scheduling; starvation |
| 8 | [`OrderMatchingEngine/`](./OrderMatchingEngine/) | One `SingleThreadExecutor` per symbol + `MpscArrayQueue`-style hand-off | *Anti-pattern lesson*: single-threaded beats locks when contention is total |

> **Already finished `TicketBooking`?** Read ATM Transfer (#6) next — it adds the
> deadlock-avoidance lesson that TicketBooking deliberately sidesteps. Then do
> #1, #2, #3 as "fill-in" exercises before tackling #7 and #8.

---

## The teaching ladder

Each rung adds **exactly one** new concurrency primitive. If a project feels
hard, the rung below is usually the missing prerequisite.

```
                                                       ┌─────────────────────────────┐
                                                       │  8. OrderMatchingEngine     │
                                                       │     (anti-pattern:          │
                                                       │      single-thread per      │
                                                       │      shard > locks)         │
                                                       └──────────────┬──────────────┘
                                                                      │
                                                       ┌──────────────┴──────────────┐
                                                       │  7. ElevatorSystem          │
                                                       │     (actors + priority      │
                                                       │      queues + scheduling)   │
                                                       └──────────────┬──────────────┘
                                                                      │
                                                       ┌──────────────┴──────────────┐
                                                       │  6. AtmTransfer             │
                                                       │     (ordered locking,       │
                                                       │      idempotency keys)      │
                                                       └──────────────┬──────────────┘
                                                                      │
                                                       ┌──────────────┴──────────────┐
                                                       │  5. HotelBooking            │
                                                       │     (range-overlap inside   │
                                                       │      critical section)      │
                                                       └──────────────┬──────────────┘
                                                                      │
                                                       ┌──────────────┴──────────────┐
                                                       │  4. TicketBooking  ⭐        │
                                                       │     (TTL soft-hold +        │
                                                       │      sweeper + 2-phase      │
                                                       │      commit)                │
                                                       └──────────────┬──────────────┘
                                                                      │
                                                       ┌──────────────┴──────────────┐
                                                       │  3. RateLimiter             │
                                                       │     (lock-free atomics +    │
                                                       │      scheduled refill)      │
                                                       └──────────────┬──────────────┘
                                                                      │
                                                       ┌──────────────┴──────────────┐
                                                       │  2. ConnectionPool          │
                                                       │     (Semaphore + bounded    │
                                                       │      wait + eviction)       │
                                                       └──────────────┬──────────────┘
                                                                      │
                                                       ┌──────────────┴──────────────┐
                                                       │  1. ParkingLot              │
                                                       │     (per-key ReentrantLock; │
                                                       │      the canonical          │
                                                       │      "lock a key, mutate    │
                                                       │      a map" pattern)        │
                                                       └─────────────────────────────┘
```

---

## What every project looks like

All projects share the same skeleton, so once you've internalized one you can
navigate any of them in 30 seconds:

```
<ProblemName>/
├── Readme.md              # Problem statement + architecture + 3 demo scenarios
├── Interview-QA.md        # Why-each-decision Q&A, mapped to interviewer prompts
├── Main.java              # 3 race scenarios that ASSERT the invariant
├── models/                # plain data classes + enums (no logic)
├── service/               # orchestration logic that holds the locks
├── repository/            # ConcurrentHashMap-backed in-memory stores
├── lock/        (optional)# pluggable lock provider, where the project has one
└── exceptions/            # one checked/unchecked per failure mode
```

Why this shape? Because it forces a **clean separation between three layers**:

| Layer | Allowed to lock? | Allowed to call I/O? |
|---|---|---|
| `models/` | No (dumb data) | No |
| `repository/` | Yes (its own internal locks) | No (in-memory only) |
| `service/` | Yes (the *compound action* lock) | Yes (payment, network, etc.) |

This separation is what makes the design swappable for a real Redis / DB
backend later — you replace the repository, not the service.

---

## When to read what

Use the table below to find the project that matches the concurrency primitive
you're trying to learn or revise *right now*.

| If you need to learn / revise... | Read first | Then |
|---|---|---|
| Per-key locking with `ConcurrentHashMap.computeIfAbsent` | `ParkingLot` | `TicketBooking` |
| `Semaphore` semantics (`acquire`, `tryAcquire(timeout)`, `release`) | `ConnectionPool` | `ParkingLot` zone-counts |
| Why `synchronized` is rarely right for an SDE-3 answer | `ConnectionPool` §6 | every project |
| Lock-free programming with `AtomicLong` / `LongAdder` | `RateLimiter` | `OrderMatchingEngine` counters |
| TTL-based "soft holds" and background sweepers | `TicketBooking` | `ConnectionPool` idle-evict |
| Two-phase commit across a slow external call | `TicketBooking` | `AtmTransfer` |
| Range / interval conflict detection under lock | `HotelBooking` | — |
| **Deadlock avoidance** via ordered lock acquisition | `AtmTransfer` | `ElevatorSystem` |
| Idempotency keys (at-most-once on retries) | `AtmTransfer` | every payment-shaped flow |
| Actor / single-threaded-shard pattern | `OrderMatchingEngine` | `ElevatorSystem` |
| `PriorityBlockingQueue` + SCAN-style scheduling | `ElevatorSystem` | — |
| `ExecutorService` lifecycle (`shutdown` vs `shutdownNow`) | `ConnectionPool` | every project's `Main` |
| `volatile` vs `AtomicReference` vs locks for status flags | `TicketBooking` §5f | `ElevatorSystem` car state |

---

## Three-pass reading recipe (per project)

For each project, do these in order. Skipping a pass will leave gaps an
interviewer will find.

### Pass 1 — "what does this *do*?" (15 min)

1. Read `Readme.md` §1–§3 (problem statement + architecture diagram).
2. Skim `Main.java` to see the three demo scenarios.
3. **Run `Main.java`** and watch the output. Don't read code yet.

### Pass 2 — "how does it work?" (45 min)

1. Read `service/<Service>.java` top-to-bottom. This is where the locks live.
2. Read `lock/` (if any) — the pluggable primitive.
3. Re-read `Readme.md` §5 (Concurrency primitives, in the order they matter).
4. Try to break it: ask "what if thread B preempts thread A right *here*?" at
   every line that touches shared state. Most of the time the answer is in a
   doc comment two lines above. When it isn't, you've found a real race —
   open an issue.

### Pass 3 — "could I defend this in an interview?" (60 min)

1. Read `Interview-QA.md` cover to cover.
2. Cover the answers, ask yourself the questions, write your own answers.
3. Compare. The *gap* between your answer and the file is your study plan.
4. For the bottom-of-page "what was deliberately left out" table, be ready to
   explain in 30 seconds each how you'd extend the system for production.

---

## Cross-cutting concepts (read once, applies everywhere)

These show up in multiple projects. Read them once, recognize them everywhere.

| Concept | Best illustration | One-line essence |
|---|---|---|
| Per-key lock via `computeIfAbsent` on `ConcurrentHashMap` | `TicketBooking#lockFor` | Atomic get-or-create of a `ReentrantLock` per shard key |
| Fair vs non-fair locks | `TicketBooking` §5a | Fair = no barging; ~10–15% slower; users perceive it as just |
| All-or-nothing multi-resource acquire | `TicketBooking#lockSeats` | Two phases inside one lock: *validate all*, then *commit all* |
| Lock-ownership check on release | `TicketBooking#unlockSeats` | A user can only release locks they own |
| `volatile` for cross-thread status flags | `TicketBooking#Booking.status` | Single-writer / many-readers, no compound updates |
| Daemon scheduled executor for cleanup | `TicketBooking#cleanupExecutor` | `tryLock` inside the tick so cleanup never blocks the hot path |
| Idempotency via dedup key | `AtmTransfer#TransferService` | A retried request returns the original result, not a second debit |
| Ordered lock acquisition | `AtmTransfer#lockBoth` | Sort by id before acquiring → no cycle → no deadlock |
| Single-threaded actor per shard | `OrderMatchingEngine#SymbolMatcher` | When contention is total, *don't* lock — serialize by shard instead |
| Backpressure via bounded queue | `ConnectionPool`, `OrderMatchingEngine` | Reject early; never let the input grow unbounded |

---

## How to compile & run

There is **no top-level build** for these examples — they are pure JDK 17
(no external dependencies). Build each one independently:

```bash
cd bel-20

# Generic recipe — replace ProblemName:
PROBLEM=TicketBooking   # or ParkingLot, ConnectionPool, ...
javac -d /tmp/lld-build $(find LLD/LldQuestions/${PROBLEM} -name '*.java')
java -cp /tmp/lld-build LLD.LldQuestions.${PROBLEM}.Main
```

Each project's `Readme.md` has the exact command in §8.

---

## How interviewers will use these problems

| Round | What they're testing | Best project to rehearse |
|---|---|---|
| **30-min phone screen** | Can you describe the data model and one race? | `ParkingLot` or `RateLimiter` |
| **60-min onsite LLD** | Can you choose lock granularity and defend it? | `TicketBooking`, `HotelBooking` |
| **60-min concurrency deep dive** | Can you reason about happens-before, fairness, deadlock? | `AtmTransfer`, `ElevatorSystem` |
| **90-min "design X" round** | Can you decompose into actors / shards with backpressure? | `OrderMatchingEngine` |
| **Bar-raiser / staff loop** | Can you explain trade-offs *you rejected*? | every project's §9 |

---

## A note on what is *not* here

These are deliberately **single-JVM** designs. The "lock provider" interfaces
exist to make the leap to distributed (Redis `SETNX`+`PEXPIRE`, ZooKeeper,
DynamoDB conditional writes, etc.) a one-class swap, but we do not implement
those. In an interview, **mention the swap, but don't volunteer to build it
unless asked** — it explodes scope and most interviewers don't have time.

We also do not cover:

- Distributed consensus (Raft / Paxos) — separate topic, separate folder.
- Database isolation levels — overlaps but is its own thing.
- Reactive / `CompletableFuture` chaining — orthogonal to the lessons here.

If an interviewer pushes on any of those, the right move is "I'd swap the
in-memory provider for a `RedisSeatLockProvider` using `SET NX PX <ttl>` and
a Lua script for the multi-key all-or-nothing acquire" — and stop there
unless they keep pulling.
