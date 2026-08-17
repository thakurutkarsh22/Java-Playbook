# Elevator System — Basic LLD

A minimal, production-shaped Low-Level Design for a multi-car elevator
system, focused on the **hardest** part of the problem: **coordinating
multiple independent actors** (cars) under shared admission control
(dispatcher), with realistic SCAN/LOOK scheduling inside each car.

This is **project #7** in the [LLD concurrency curriculum](../README.md).
It is the canonical worked example for the pattern:

> *"When a resource has its own internal state machine that ticks
> continuously, give it its OWN thread (actor model). External callers
> communicate via a mailbox queue, NOT via shared locks."*

---

## 1. Problem statement

A building has N elevators. Users on the floors press hall buttons
(up/down). Users inside the cars press destination buttons. The system
must:

| Invariant | Plain English |
|---|---|
| **Serve every request** | Every button press is eventually fulfilled. |
| **Dispatch to nearest car** | Hall calls go to the car with lowest cost-to-serve. |
| **SCAN scheduling** | An UP-moving car serves every UP request above it before reversing. |
| **No deadlock** | Two simultaneous hall calls from opposite ends can't gridlock the dispatcher. |
| **Actor isolation** | Each car owns its own state; no two threads mutate the same car's stops. |

---

## 2. Architecture

```
                        ┌──────────────────────────────┐
                        │            Main              │
                        │  (3 demo scenarios)          │
                        └───────────────┬──────────────┘
                                        │
                                        ▼
                        ┌──────────────────────────────┐
                        │         Dispatcher           │
                        │  (stateless; chooses car     │
                        │   by costForHallCall)        │
                        └──────────────┬───────────────┘
                                       │ submit(Request)
              ┌────────────────────────┼────────────────────────┐
              ▼                        ▼                        ▼
   ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐
   │  Elevator E1        │  │  Elevator E2        │  │  Elevator E3        │
   │  ─ mailbox          │  │  ─ mailbox          │  │  ─ mailbox          │
   │    (LinkedBlocking) │  │    (LinkedBlocking) │  │    (LinkedBlocking) │
   │  ─ upStops          │  │  ─ upStops          │  │  ─ upStops          │
   │    (SkipListSet)    │  │    (SkipListSet)    │  │    (SkipListSet)    │
   │  ─ downStops        │  │  ─ downStops        │  │  ─ downStops        │
   │  ─ ONE driver       │  │  ─ ONE driver       │  │  ─ ONE driver       │
   │    thread (actor)   │  │    thread (actor)   │  │    thread (actor)   │
   └─────────────────────┘  └─────────────────────┘  └─────────────────────┘
```

**Two crucial properties:**

1. **One thread per car.** All mutation of a car's state happens on its
   own `ScheduledExecutorService`'s single thread. No locks needed
   *inside* the car.

2. **Mailbox-driven.** External callers (Dispatcher, riders) push
   `Request` objects into the car's `LinkedBlockingQueue`. They never
   touch the car's state directly.

---

## 3. Directory layout

```
ElevatorSystem/
├── Main.java                          # 3 demo scenarios
├── Readme.md                          # this file
├── Interview-QA.md                    # SDE-3 Q&A
├── models/
│   ├── Direction.java                 # UP | DOWN | IDLE
│   └── Request.java                   # HALL_CALL or CAR_CALL
└── service/
    ├── Elevator.java                  # ★ THE ACTOR; owns its state
    └── Dispatcher.java                # stateless cost-based router
```

---

## 4. Concurrency primitives, in the order they matter

### 4a. Actor model — ONE thread per car

This is the headline lesson. Instead of "lock the car's state, mutate
it, unlock", we say "all mutations happen on one thread; everyone else
sends messages".

```java
ScheduledExecutorService driver = Executors.newSingleThreadScheduledExecutor(...);
driver.scheduleAtFixedRate(this::tick, 0, tickMillis, MILLISECONDS);
```

The `tick()` body is the ONLY thing that reads or writes `upStops`,
`downStops`, `direction`, `currentFloor`. So they cannot race with
themselves. No locks. No happens-before reasoning. Just: "this one
thread owns this state."

### 4b. `LinkedBlockingQueue<Request>` mailbox

External callers `mailbox.offer(request)`. The `tick()` body calls
`mailbox.poll()` in a loop to drain. Producer and consumer never share a
critical section beyond the queue's own internals.

Why a *queue* and not just direct method calls into the car?

- **Concurrency**: many producers (riders, dispatcher) hit one consumer
  (driver thread). Queue gives lock-free FIFO ordering for free.
- **Back-pressure**: a `BlockingQueue` with bounded capacity could reject
  or throttle. Unbounded for simplicity here.
- **Decoupling**: producers don't wait for the consumer to be ready,
  they just enqueue and continue.

### 4c. Per-car `ConcurrentSkipListSet<Integer>` for stops

Mutated only inside `tick()`, so technically a `TreeSet` would suffice.
We use `ConcurrentSkipListSet` defensively so that diagnostic reads from
other threads (the Dispatcher's `costForHallCall`) are safe and
*weakly-consistent*.

Why an ordered set? Because the next stop to serve depends on direction:

| Direction | Next stop |
|---|---|
| UP | `upStops.ceiling(currentFloor)` — smallest >= cur |
| DOWN | `downStops.floor(currentFloor)` — largest <= cur |

Both are O(log n).

### 4d. `volatile Direction direction` + `AtomicInteger currentFloor`

These are the car's *public observables*. The Dispatcher reads them
without ever entering the car's actor thread. That's a *racy read by
design*: by the time the dispatcher acts on the value, one tick may have
elapsed and the car has moved a floor. That's fine — the dispatch
estimate doesn't need to be perfect.

`volatile` / `AtomicInteger` give a happens-before edge for any reader
without forcing them to hop onto the car's thread.

### 4e. Stateless `Dispatcher`

No locks anywhere. Each call to `dispatchHallCall` is:

```java
for each car: cost = car.costForHallCall(floor, dir);   // racy read, OK
pick the min;
winner.submit(request);                                 // lock-free enqueue
```

Two concurrent dispatches may pick the same car. That's fine — the car
will serve them in arrival order.

### 4f. Exception-safe scheduled task

```java
private void tick() {
    if (!running) return;
    try { tickBody(); }
    catch (Throwable t) { logAndContinue(t); }
}
```

`ScheduledExecutorService.scheduleAtFixedRate` **silently kills the
recurring task** if any execution throws. This try/catch wrapper is a
must for production-quality recurring jobs — and is exactly how
`Hikari`, `Caffeine`, and most Java schedulers wrap their internal
tasks.

---

## 5. SCAN vs LOOK scheduling

This project uses **LOOK** (also called "elevator algorithm"):

> *"Keep moving in the current direction until there's no more work in
> that direction, THEN reverse."*

| Algorithm | Reverses at... | Pros | Cons |
|---|---|---|---|
| FCFS | Never reverses; just queue order | Trivial | Cars zig-zag; terrible latency |
| SCAN | Always rides to the top/bottom floor | Predictable | Wastes travel past empty floors |
| **LOOK** | The *last requested* floor in this direction | No wasted travel | Slightly more complex |
| C-LOOK | Always serves one direction, then jumps back | Fairness | Jumps look strange to riders |

LOOK is what real elevators implement. Our `nextDestination()` is the
LOOK algorithm:

```java
if (direction == UP) {
    Integer above = upStops.ceiling(cur);
    if (above != null) return above;
    Integer below = downStops.floor(cur);
    if (below != null) { direction = DOWN; return below; }
    return null;            // IDLE
}
// symmetric for DOWN
```

---

## 6. The dispatcher's cost function

```java
costForHallCall(int requestedFloor, Direction requestedDirection):
    distance = |requestedFloor - currentFloor|
    if direction == IDLE:           return distance
    if requested is on-the-way:     return distance        // no detour
    else:                           return distance + rangePenalty
```

"On-the-way" means:
- car is UP and request is above us asking to go UP, OR
- car is DOWN and request is below us asking to go DOWN.

The `rangePenalty` (half the floor span) approximates the cost of the
car finishing its current leg, reversing, and coming back. For a real
production dispatcher this would be replaced by a more sophisticated
estimator, but for an LLD this captures the dominant terms.

---

## 7. Demo (`Main.java`) — three scenarios

### Scenario 1 — Dispatcher picks the nearer car

Two cars, two simultaneous hall calls on opposite ends.

```
=== Scenario 1: 2 cars, 2 simultaneous hall calls on opposite ends ===
  call(floor=2 UP)  routed to E1 (expected E1)
  call(floor=8 DOWN) routed to E2 (expected E2)
  [E1] STOP at floor 2 (UP)
  [E2] STOP at floor 8 (DOWN)
  final E1@floor=2 E2@floor=8
```

### Scenario 2 — Single elevator serves stops in SCAN order

Three calls submitted in scrambled order; car visits them in ascending
floor order.

```
=== Scenario 2: one elevator serves 3,7,9 in SCAN order ===
  [E] STOP at floor 3 (UP)
  [E] STOP at floor 7 (UP)
  [E] STOP at floor 9 (UP)
```

### Scenario 3 — LOOK reverses on direction exhaustion

Car goes UP to 7, then reverses to 2 because there's no more upward
work.

```
=== Scenario 3: LOOK reverses direction when no more UP work ===
  [E] STOP at floor 7 (UP)
  [E] STOP at floor 2 (DOWN)
  expected: visit 7 (UP), reverse, visit 2 (DOWN). final floor=2
```

---

## 8. How to run

```bash
cd bel-20
javac -d /tmp/el-build $(find LLD/LldQuestions/ElevatorSystem -name '*.java')
java -cp /tmp/el-build LLD.LldQuestions.ElevatorSystem.Main
```

---

## 9. What was deliberately left out

| Skipped | Why fine for basic LLD | What to add for production |
|---|---|---|
| Variable car capacity (max riders) | Treat as unlimited | Add `Semaphore(capacity)` per car; admission control like ConnectionPool |
| Door open/close timing | Single-tick stop | Add a 2-3 tick "door dwell" with a separate state machine |
| Priority floors (lobby, exec) | Equal priority | `PriorityBlockingQueue<Request>` keyed by priority; reweight cost |
| Multi-zone elevators | All cars serve all floors | Subset per car; dispatcher only considers cars that cover the request |
| Power saving | Cars run continuously | Park unused cars at strategic floors; wake-on-call |
| Starvation | Pure LOOK can starve a corner case | Aging: after a request has waited > T, force-include it in the next SCAN |
| Faults / maintenance | All cars always up | Add `Status state` enum; dispatcher excludes OUT_OF_SERVICE |
| Predictive dispatch | Greedy nearest-cost | ML / heuristic dispatch trained on historical traffic patterns |

---

## 10. Interview-ready one-liner

> Each elevator is an **actor**: a `SingleThreadScheduledExecutor` owns
> all mutation of `upStops`, `downStops`, `direction`, `currentFloor` —
> no locks needed inside the car because there's only one writer. External
> callers communicate via a `LinkedBlockingQueue` mailbox. The Dispatcher
> is stateless: it racy-reads each car's public observables (`volatile
> direction`, `AtomicInteger currentFloor`), computes a SCAN-aware cost,
> and forwards the request to the cheapest car. The car services stops
> in **LOOK** order using two `ConcurrentSkipListSet<Integer>` (one per
> direction), giving `O(log n)` next-stop lookup. The whole design has
> zero `ReentrantLock` and zero `synchronized` — actors replace locks
> when one resource has continuous state.
