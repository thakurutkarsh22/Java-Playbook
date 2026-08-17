# Elevator System — Interview Q&A Cheat Sheet

Companion to `Readme.md`. The Readme explains the *design*; this file
captures the SDE-3-level questions an interviewer will actually ask.
This project's headline lesson is the **actor model**: replacing locks
with a single-threaded executor + mailbox.

---

## Q1. Why actors instead of locks for each car?

**TL;DR** — Because the car has *continuous internal state* that ticks
every interval, regardless of external traffic. Locks would have the
driver thread contend with appenders on every tick. Actors avoid the
contention by definition.

### The "locks everywhere" alternative

```java
class Elevator {
    final ReentrantLock lock = new ReentrantLock();
    NavigableSet<Integer> upStops;

    public void submitHallCall(...) {
        lock.lock();
        try { upStops.add(...); } finally { lock.unlock(); }
    }

    void tick() {
        lock.lock();
        try { ... drain, advance, etc. ... } finally { lock.unlock(); }
    }
}
```

Problems:

1. **Constant lock thrash**. Tick runs every 50 ms — that's 20
   acquire+release per second per car, every car, forever. Even
   uncontended `ReentrantLock` is ~20 ns; the *contended* case under
   busy traffic gets worse.
2. **The actor has nothing else to do.** It's already running on its own
   thread. The lock is buying us nothing — there's no other writer.

### The actor view

```text
External callers → mailbox.offer(request)     -- O(1), lock-free
Actor thread     → mailbox.poll() then mutate -- O(1), no contention
```

The mailbox itself has internal locking (LinkedBlockingQueue uses two
locks: one for head, one for tail), but that contention is amortized.
The actor's *state* mutations are completely lock-free.

### When are actors NOT right?

When the resource has long idle periods. A car that gets ZERO traffic
for an hour wastes a thread (or a thread-pool slot). Real elevator
firmware schedules the tick adaptively — sleeping longer when idle. For
the LLD, a fixed-rate tick is fine.

Compare to `ParkingLot` (project #1) where the per-floor lock IS the
right answer: the floor has no internal state that ticks on its own;
it's purely reactive to park/unpark.

---

## Q2. Why is the dispatcher stateless?

**TL;DR** — Because hall-call dispatch is a **pure function** of (the
list of cars, the request). No state to mutate ⇒ no locks needed ⇒
trivially parallel.

### What COULD make the dispatcher stateful?

- A queue of un-assigned hall calls that get re-balanced as cars free up.
- A round-robin index for fairness.
- A history-based estimator for prediction.

We didn't include any of those. For a real production system, a stateful
queue is common — `PriorityBlockingQueue<Request>` with the dispatcher
draining and re-assigning every few seconds. Same actor pattern: one
thread owns the dispatcher's state, everyone else `offer`s.

### What happens if two dispatches race?

```text
Thread A: dispatchHallCall(floor=2 UP)
  - reads E1.cost = 1, E2.cost = 7 -> picks E1

Thread B: dispatchHallCall(floor=2 UP)  (concurrent)
  - reads E1.cost = 1 (same time), E2.cost = 7 -> picks E1

Both submit to E1. E1's mailbox gets two messages.
E1 ticks, drains both, adds floor=2 to upStops (idempotent set add).
```

Net result: floor 2 is served once. The "double-dispatch" was harmless
because the underlying state (`upStops`) is a *set*, not a multiset. If
the underlying state were a counter ("number of requests at floor 2"),
we'd need to dedupe somewhere.

---

## Q3. Walk me through the SCAN/LOOK algorithm with numbers.

**TL;DR** — Car at floor 5, going UP. Stops queued: {2, 7, 9, 3, 4}.
Service order: 7, 9 (no more up), reverse to DOWN, 4, 3, 2.

### Step by step

```text
state: cur=5, dir=UP, upStops={7, 9}, downStops={2, 3, 4}
       (floors > 5 went into upStops; floors < 5 into downStops on submission)

tick #1: cur=5, dir=UP, upStops.remove(5)? no.
         next = ceiling(5, upStops) = 7. cur=6, dir=UP.

tick #2: cur=6, dir=UP, upStops.remove(6)? no.
         next = ceiling(6, upStops) = 7. cur=7, dir=UP.

tick #3: cur=7, dir=UP, upStops.remove(7)? YES. STOP at 7.
         next = ceiling(7, upStops) = 9. cur=8, dir=UP.

tick #4: cur=8, next = 9. cur=9.

tick #5: cur=9, upStops.remove(9). STOP at 9.
         next = ceiling(9, upStops) = null.
         below = floor(9, downStops) = 4. dir=DOWN. cur=8.

tick #6: cur=8, dir=DOWN, downStops.remove(8)? no.
         next = floor(8, downStops) = 4. cur=7.

... and so on down to 4, then 3, then 2.
```

Total stops visited: 7, 9, 4, 3, 2 — five real stops in the LOOK order.

### Why is this right?

A user at floor 4 going DOWN clicked their button while the car was
above them, moving away. They wait until the car finishes its upward
leg, then catch it on the way down. From the user's POV, this is "feels
fair" — the car came back as soon as it ran out of higher work.

### Where it starves

Consider: car continuously gets new UP requests at floors 10, 11, 12 ...
A user at floor 1 going DOWN waits forever. Mitigation:

- **Aging**: bump priority after waiting > 30 s. Real systems use this.
- **Bounded SCAN**: serve at most K up-floors before reversing.
- **Two-elevator coordination**: dedicate one elevator to "down trips
  during morning rush".

In an interview, name the problem and mention aging.

---

## Q4. Why two ordered sets (`upStops`, `downStops`) instead of one priority queue?

**TL;DR** — Because the "next destination" predicate is *direction-
dependent*. A priority queue would have to be re-sorted on direction
change.

### What you'd write with one priority queue

```java
PriorityQueue<Integer> stops = new PriorityQueue<>(...);

Integer nextStop() {
    Comparator<Integer> c = direction == UP
        ? Comparator.naturalOrder()
        : Comparator.reverseOrder();
    // pq doesn't support changing comparator at runtime; you'd rebuild it
}
```

This is bad: changing the comparator means re-heapifying ALL elements
(O(n)). Direction changes are frequent, so this is O(n) per direction
change.

### Two `NavigableSet`s

```java
Integer nextStop() {
    return direction == UP
        ? upStops.ceiling(cur)
        : downStops.floor(cur);
}
```

Both queries are O(log n). Direction change is O(1) (we just look at the
other set).

### What if a single physical stop is wanted in both directions?

It's automatically in only one bucket — the one matching the requested
direction at submission time. If a user at floor 5 wants to go UP, that
goes into `upStops`. If they want DOWN, `downStops`. The same physical
floor button literally exists twice on real elevators (▲ and ▼), so
this duplication is realistic.

---

## Q5. Why does `scheduleAtFixedRate` need the try/catch wrapper?

**TL;DR** — Because **it silently kills the recurring task on any
uncaught exception**. This is one of the most-bitten-by gotchas in Java.

### What the JDK actually does

From `ScheduledThreadPoolExecutor` Javadoc:

> If any execution of the task encounters an exception, subsequent
> executions are suppressed.

No log. No re-throw. The `ScheduledFuture` you got back transitions to
"failed", but if you didn't store it (and most code doesn't), the
failure is invisible.

### What this looks like in production

```text
[normal logs for hours]
[silent NullPointerException in tick #84321]
[no logs ever again]
[engineer wakes up at 3am: "cars are stuck"]
```

### The fix

```java
private void tick() {
    try { tickBody(); }
    catch (Throwable t) {
        logger.error("[" + id + "] tick threw", t);
        // optional: bump a "tickErrors" counter; alert if > N
    }
}
```

This is **mandatory boilerplate** for any recurring scheduled task. Same
pattern appears in HikariCP, Caffeine, Lettuce, and every other library
that schedules background work.

### Bonus tip

Catch `Throwable`, not `Exception`. `OutOfMemoryError` is an `Error`,
not an `Exception`, and a single OOM should NOT permanently disable your
elevator. (Real disaster recovery is more complex, but at minimum the
log should fire.)

---

## Q6. How would you bound the mailbox? What happens if a car is overloaded?

**TL;DR** — Use a bounded `ArrayBlockingQueue`. On full, `offer` returns
`false` — the producer (dispatcher) sees the rejection and either
retries another car or returns 503 to the user.

### The current code: `LinkedBlockingQueue` (unbounded)

```java
LinkedBlockingQueue<Request> mailbox = new LinkedBlockingQueue<>();
mailbox.offer(request);   // always returns true
```

Pro: no producer ever blocks.
Con: under sustained overload, mailbox grows without bound → OOM.

### The bounded variant

```java
BlockingQueue<Request> mailbox = new ArrayBlockingQueue<>(1000);
boolean accepted = mailbox.offer(request);
if (!accepted) {
    // policy: reject the request? requeue to dispatcher? page someone?
}
```

For an elevator, "reject" is fine — the rider can press the button
again, or another car can be tried. For a kafka-style pipeline, you'd
want to back-pressure the producer.

### How to pick the bound?

`bound = arrival_rate × max_acceptable_wait`. For an elevator at 1
request/sec and max 60 s wait, bound = 60. If we ever back up beyond
that, we have a real problem and rejection is the correct surface area.

---

## Q7. What's the dispatcher's failure mode if a car is stuck (driver thread blocked)?

**TL;DR** — The dispatcher keeps dispatching to the stuck car based on
its **last-published** state. Mitigation: heartbeats + a `healthy`
flag.

### The race-condition you're guarding against

```text
Car E1's driver thread enters an infinite loop (bug)
  - mailbox keeps accepting (E1.offer succeeds)
  - currentFloor frozen at old value
  - direction frozen at old value
  - Dispatcher keeps picking E1 because its racy reads say "best cost"
```

### Fix: heartbeats

```java
private final AtomicLong lastTickAtNanos = new AtomicLong(...);

void tick() {
    try { tickBody(); }
    finally { lastTickAtNanos.set(System.nanoTime()); }
}

public boolean isHealthy() {
    return (System.nanoTime() - lastTickAtNanos.get()) < 2 * tickMillis * 1_000_000L;
}
```

Dispatcher excludes unhealthy cars:

```java
for (Elevator e : elevators) {
    if (!e.isHealthy()) continue;
    int cost = e.costForHallCall(...);
    ...
}
```

### Belt-and-suspenders

Also kill+restart the driver thread on unhealthy detection. Production
elevator firmware has watchdog timers in hardware that physically
disconnect a stuck CPU. We're software, but the pattern is the same.

---

## Q8. How does this scale to a 100-car installation?

**TL;DR** — Each car is independent so cars scale linearly. The
dispatcher's `for each car` loop becomes the bottleneck; replace with a
priority queue keyed by cost-bucket.

### The two scalability axes

| Resource | Scales how? |
|---|---|
| Per-car state | Linear in cars; each on its own thread |
| Dispatcher's car-pick | O(N) per dispatch |
| Mailbox per car | O(1) per dispatch |
| Driver thread per car | Linear in cars |

At 100 cars, the dispatcher loop is 100 reads × cheap math = ~10 µs.
Still fast. At 1000 cars, it's 100 µs. Getting slower.

### Faster dispatcher

Maintain a sorted structure keyed by cost-bucket:

```java
PriorityQueue<Elevator> readyCars
    = new PriorityQueue<>(Comparator.comparingInt(e -> e.costForHallCall(...)));
```

But `costForHallCall` depends on the *request*, so you can't pre-sort.
What you CAN pre-sort by: current position. Bucket cars by floor, then
on dispatch, look up the bucket nearest the request.

```java
NavigableMap<Integer, Set<Elevator>> carsByFloor;
Map.Entry<Integer, Set<Elevator>> closest = carsByFloor.ceilingEntry(reqFloor);
```

O(log n) car lookup. The bucket is small (1-2 cars per floor), so the
fine-grained pick is O(1).

### When is 1000+ cars realistic?

Some Shanghai skyscrapers have ~80 cars per building. A real "100 cars"
problem is more likely a chain of buildings — at that point, sharding
the dispatcher per building is the right move (per-building Dispatcher
instance, each independent).

---

## Q9. Can the rider's "press destination button" race with another rider's button press?

**TL;DR** — No. Both go through the same mailbox of the same car;
they're serialized by mailbox FIFO.

### The scenario

Two riders in E1. Rider A presses "5". Rider B presses "8" 10 ms later.

```text
Rider A thread: E1.submit(CAR_CALL(5))
Rider B thread: E1.submit(CAR_CALL(8))

LinkedBlockingQueue ensures FIFO of `offer()` operations.
   queue contents: [CAR_CALL(5), CAR_CALL(8)]

Driver tick: drain both, add to upStops {5, 8}.
LOOK order: 5 first, then 8.
```

### What if they press at "exactly the same time"?

The queue's internal lock serializes the offer pair. Whichever wins the
internal lock first goes first. From the user POV the wait time
difference is microseconds — invisible.

### What if a destination button race could matter?

It would only matter if the actions affected the *cost* of subsequent
dispatches. They don't — both just add stops to the same set. No
correctness issue.

---

## Q10. How would you write deterministic tests for this?

**TL;DR** — Inject a virtual clock + tick controller. Don't rely on
`Thread.sleep` for ordering.

### The bad way (real wall-clock)

```java
elevator.start(50);     // 50 ms tick
elevator.submit(...);
Thread.sleep(200);      // hope 4 ticks happened
assert ...;
```

Flaky: under load `Thread.sleep(200)` might give you 3 ticks or 5. CI
machines fail intermittently. **Never** test concurrency with
`Thread.sleep`.

### The good way

```java
class TestClock { volatile long now; }
class TestableElevator extends Elevator {
    void doOneTick() { tickBody(); }
}

TestableElevator e = new TestableElevator(...);
e.submit(call(2, UP));
e.doOneTick();           // deterministic
assertThat(e.currentFloor()).isEqualTo(2);
e.doOneTick();
assertThat(e.lastStopped()).isEqualTo(2);
```

### Property tests for SCAN correctness

```text
Given:
  - random sequence of HALL_CALL/CAR_CALL submissions
  - elevator with min/max floors
Run:
  - ticks until idle
Assert:
  - every submitted floor was visited
  - within any contiguous-direction run, stops are monotonic
```

`jqwik` or `Hypothesis` for the property framework. Shrinks counter-
examples to the smallest failing input.

### Where the demo cuts corners

The demo uses real time + `waitUntilIdle` because pedagogically it's
easier to follow. A real test suite would never do this — but for a
demo you want to see the elevator visually progress.

---

## Bonus — quickfire round

| Question | Answer (≤2 lines) |
|---|---|
| Why is `Direction direction` volatile, but `currentFloor` an AtomicInteger? | Both are public observables read by the dispatcher; both need happens-before. AtomicInteger gives the same guarantee as volatile-int *plus* atomic increment if needed later. |
| Why is `pendingStops()` including `mailbox.size()`? | So observers between a `submit` and the next tick still see the work as pending. Without this, `waitUntilIdle` returns prematurely. |
| Why daemon thread for the driver? | So JVM exit isn't held up. An elevator with no running JVM is fine — the user pressing the button after restart starts fresh. |
| When would you go BACK to locks instead of actors? | When the resource is *purely reactive* (no internal ticking). `ParkingLot` is reactive; `Elevator` is not. |
| Can you reuse this for a robot fleet? | Yes — same pattern. Each robot is an actor with a mailbox. A fleet coordinator is the dispatcher. The cost function differs (energy, congestion, task affinity). |
| What's the JDK class closest to this design? | `akka` / `Erlang/OTP` outside the JDK; in plain JDK, `SingleThreadExecutor` + `BlockingQueue` is the canonical actor pattern. |
| What if you need millions of elevators? | One thread per car becomes wasteful. Switch to virtual threads (Project Loom) or run K cars per thread with a multiplexer. |
