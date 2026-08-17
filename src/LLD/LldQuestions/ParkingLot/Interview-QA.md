# Parking Lot — Interview Q&A Cheat Sheet

Companion to `Readme.md`. The Readme explains the *design*; this file
captures the questions an SDE-3 interviewer will actually ask, with the
reasoning behind each decision.

---

## Q1. Why a `Semaphore` instead of an `AtomicInteger` for the free-count?

**TL;DR** — Because we want *blocking* semantics later, and a Semaphore gives
us a wait queue for free.

### What an AtomicInteger forces you to write

```java
AtomicInteger free = new AtomicInteger(5);

// "try" path
if (free.decrementAndGet() < 0) {
    free.incrementAndGet();  // undo
    return null;
}
```

That's correct but has two problems:

1. **No blocking variant.** Real lots want "wait up to 30 seconds for a
   spot to free up". With AtomicInteger you'd have to write a busy-wait
   loop with `Thread.sleep()` and reimplement back-off — i.e., reimplement
   `Semaphore` badly.
2. **No fairness control.** `Semaphore(N, fair=true)` gives you FIFO
   acquisition out of the box. AtomicInteger has no concept of waiters at
   all.

### What Semaphore gives you for free

- `acquire()`, `tryAcquire()`, `tryAcquire(timeout, unit)` — three policies
  with one primitive.
- A built-in wait queue, optionally fair.
- `availablePermits()` for metrics that report a *consistent lower bound*.

### When AtomicInteger is right

- Pure counters that never block (`requestsPerSec`, `cacheHits`).
- When you want lock-free updates with no notion of "how many are left to
  grab right now" — e.g. `RateLimiter`'s token bucket (project #3).

---

## Q2. Why is `ParkingLotService` lock-free? Isn't `park()` a compound action?

**TL;DR** — No, because the compound action is *entirely inside a single
floor*. The service is a router.

### What `park()` actually does

```text
for each floor:
    spot = floor.reserveAndClaim(vehicle)        // <- compound, locks INSIDE the floor
    if spot != null: return makeTicket(...)
throw LotFullException
```

Notice the iteration is **read-only** at the service level — we don't mutate
service-level state, we just call the next floor when the previous one fails.
The floor itself owns the locks for the only mutation that happens.

### When would you put a lock at the service level?

If `park()` had to *atomically* check state across floors — e.g. "park on the
floor with the highest free-count *as of right now*". Then you'd need either:

- a service-level lock around the whole floor-picking step, OR
- a `PriorityBlockingQueue<ParkingFloor>` ordered by free count.

The latter scales much better. Service-level locks are usually the wrong
answer; if you're reaching for one, ask first whether you can shard.

---

## Q3. Walk me through the semaphore-then-lock acquisition. Why this order?

**TL;DR** — Semaphore is *admission control*. Lock is *selection*. Doing the
counter check first means the 90% "lot is full" case never even touches the
lock, so the lock stays uncontended.

### Worked example: 3 CAR spots, 4 cars race

```text
t=0     Semaphore(CAR) = 3 permits

t=1     u1 tryAcquire(CAR) -> 3→2 ✓
        u1 floorLock.lock()
        u1 scan: C1 free, occupy C1
        u1 floorLock.unlock()

t=1.01  u2 tryAcquire(CAR) -> 2→1 ✓
        u2 floorLock.lock()   <- blocks ~10 µs on u1
        u2 scan: C2 free, occupy C2
        u2 floorLock.unlock()

t=1.02  u3 tryAcquire(CAR) -> 1→0 ✓
        u3 ... C3

t=1.03  u4 tryAcquire(CAR) -> 0, FAILS
        u4 NEVER took the floor lock.
```

The 4th car was rejected by a single CAS-style decrement. The lock was held
for ~30 µs total across three threads. Throughput in this design is bounded
by the lock; with N CAR spots the lock is held N times during the burst, then
all subsequent arrivals short-circuit through the semaphore alone.

### Why is the release order *opposite*?

```text
floorLock.lock()
  spot.release()             // mutate FIRST
floorLock.unlock()
semaphore.release()          // signal AFTERWARDS
```

If we did `semaphore.release()` *before* `spot.release()`, a thread waking on
the permit could enter the critical section while `occupiedBy` was still set,
fail the scan, and have to undo the permit. The invariant we're preserving
is:

> **"Permit available ⇒ at least one spot of that size is genuinely free."**

Releasing the lock first guarantees the read side sees the free spot before
the permit is given out.

---

## Q4. Why per-floor lock granularity? Why not per-spot, or one global lock?

**TL;DR** — Lock-per-spot has more locks than spots → more memory and lock
ordering nightmares for the up-grade case. One global lock destroys
parallelism between floors. Per-floor is the sweet spot.

| Granularity | Pros | Cons |
|---|---|---|
| One global lock | Trivially correct | Whole lot serializes — unusable above ~100 vehicles/min |
| **Per-floor** ← chosen | Different floors fully parallel; tiny critical sections | Slight memory overhead per floor |
| Per-row | Higher parallelism on same floor | Complex; up-grade across rows needs lock ordering |
| Per-spot | Maximum parallelism on paper | (a) More locks than spots = more memory, (b) up-grade scan across many spots = lock-ordering / deadlock risk |

The critical section under per-floor is "scan list, set one field, return" —
microseconds. Going finer trades real complexity for a savings that doesn't
show up in any benchmark below ~100k QPS *per floor*.

### When does per-spot win?

When the spot lookup is O(spots) and the scan dominates the lock duration —
i.e., a 5000-spot floor. In that case you'd either index spots by size +
status (so the scan becomes O(1)) or use a `ConcurrentLinkedQueue<ParkingSpot>`
of free-of-size-X spots, which gives O(1) `poll()` with no explicit lock.

---

## Q5. What if `tryAcquire` succeeds but no spot is free in the scan? Is that possible?

**TL;DR** — No. If it happens, you have a *bug* — the semaphore and the spot
list got out of sync. We throw `IllegalStateException` on that path to make
the bug visible.

### Why is the invariant maintained?

```
Semaphore freeBySize[size] == (count of spots where size = X AND occupiedBy == null)
```

Both sides change only inside the per-floor lock or via `Semaphore.release()`
**after** the lock has unlocked. Specifically:

- `park()`: `tryAcquire()` decrements; then *inside* the lock we flip exactly
  one `occupiedBy` from null → plate. So both sides go down by 1. ✓
- `unpark()`: inside the lock we flip exactly one `occupiedBy` from plate →
  null; then `release()` increments. Both sides go up by 1. ✓

The invariant is therefore maintained step-by-step. The defensive `throw` in
`reserveAndClaim` is for the **bug-detection case** — if some future change
to ParkingSpot allows a third mutation path, that throw is the alarm bell.

### What if the JVM crashes between the two steps?

The in-memory state is gone anyway. In a real system with a DB:

- `park()` runs in a transaction: `UPDATE spot SET occupied_by = ?
  WHERE id = ? AND occupied_by IS NULL` returning the rowcount. If 0, you
  bail. The semaphore is a runtime cache that gets rebuilt from the DB on
  start-up.

---

## Q6. How do you handle the up-grade case (BIKE into CAR spot)?

**TL;DR** — Walk sizes in *ascending* order starting at the requested size.

### Code

```java
for (VehicleSize candidate : VehicleSize.values()) {
    if (candidate.compareTo(requested) < 0) continue;   // skip too-small
    Semaphore permits = freeBySize.get(candidate);
    if (!permits.tryAcquire()) continue;                // exhausted, escalate
    // take the floor lock + claim a spot of `candidate` size
}
```

### Why ascending (smallest acceptable first)?

A TRUCK spot occupied by a BIKE is **waste**. We want to preserve big spots
for big vehicles that have no fallback. This is the *best-fit* allocator
pattern.

### Why not "best-fit" exhaustively (try EVERY smaller variant before TRUCK)?

We already do — `VehicleSize.values()` iterates `BIKE, CAR, TRUCK`. For a
BIKE we try BIKE (own size), then CAR, then TRUCK. For a TRUCK we only try
TRUCK. Order is enforced by the enum declaration.

### Will this ever deadlock?

No. Each iteration is an *attempt*, not a hold. We never hold two semaphores
at once. Either `tryAcquire` succeeds and we move into the lock-then-claim
phase, or it fails and we move to the next size. The lock is taken at most
once per `park()` call.

---

## Q7. Why is the `ticketRepository` a `ConcurrentHashMap` and not `HashMap`?

**TL;DR** — Multiple gates write tickets in parallel; many displays read
them concurrently. CHM gives lock-striped reads (no contention) and
serialized writes per bucket.

### What goes wrong with `HashMap`?

Three known failure modes, in order of severity:

1. **Lost writes.** No happens-before edge between thread A's `put()` and
   thread B's `get()`. A ticket saved at gate-1 may be invisible to gate-2
   forever.
2. **Structural corruption.** Concurrent `put` during resize is unsafe.
   Java 7: infinite loop on `get` (the famous pre-2014 outage). Java 8+:
   the loop is gone but you can still drop entries.
3. **`size()` lies.** Returns a stale snapshot.

### What about `Collections.synchronizedMap(new HashMap<>())`?

Works for correctness but **serializes every read and write through one
monitor**. CHM uses lock-striping so unrelated keys don't contend.
Synchronized map is a 1995 answer to a 2025 problem.

### Could we go even further — `Caffeine`?

For a real production gate, yes. Caffeine adds size-bounded eviction +
expire-after-write, which matches the use case ("tickets older than 24h are
no longer valid"). CHM is fine for the LLD demo.

---

## Q8. Is your `release()` thread-safe with a concurrent `park()` of the same plate?

**TL;DR** — Yes, because both run inside the per-floor lock, and the
ownership check rejects releases for plates that don't own the spot.

### The race

```text
Thread A: park(plate="ABC")          Thread B: unpark(ticketId)
                                       ticket.getPlate() == "ABC"
  park enters floorLock                 (waiting)
  spot.occupy("ABC")                     ...
  releases floorLock
                                       acquires floorLock
                                       spot.getOccupiedBy() = "ABC" ✓
                                       spot.release()
                                       releases floorLock
                                       semaphore.release()
```

This is fine — the unpark happens *after* the park. The mutual exclusion is
the floor lock; the semaphore is not strictly needed for safety here, but
it's needed for the *next* park to find this spot.

### The bad case (ticket swap at the gate)

```text
attacker presents Alice's ticket for Bob's car

Thread X: unpark(aliceTicketId)
  ticket.getPlate() == "ALICE"
  spot.getOccupiedBy() == "BOB"        <- inserted by previous park
  throw InvalidTicketException
```

Caught in `ParkingLotService.unpark()` before we touch the floor. The ticket
plate check is a **server-side authorization** of the operation — we never
trust the ticket id alone.

---

## Q9. How would you scale this to a chain of lots across cities?

**TL;DR** — The single-JVM design becomes a single-shard pattern, where each
parking lot is a shard. Across the chain you add a *router*; inside each lot
the code we just wrote runs unchanged.

### Architectural moves

| Single-JVM design | Multi-region production |
|---|---|
| `ParkingLotService` per JVM | One service instance per lot; load balancer in front |
| `Semaphore` permits | `Redis INCR/DECR` with a Lua script for atomic "try-acquire + claim" |
| `ReentrantLock floorLock` | Postgres row lock or Redis `SETNX` on `floor:F1:claim` |
| `TicketRepository` (CHM) | DynamoDB or Postgres; ticket id is the partition key |
| `ScheduledExecutorService` (if any) | Per-lot background worker, or a centralized cron with a fan-out |

### What stays the same

The **invariants** stay the same. The "permit available ⇒ free spot exists"
invariant is preserved by the Lua script that does the INCR/DECR + the
row-claim in one round-trip. The semaphore-then-lock ordering becomes
"DECR-then-conditional-update" in Redis terms.

### What's new

- **Idempotency**: ticket creation needs an idempotency key so a retry
  doesn't issue two tickets. Same pattern as `AtmTransfer` (project #6).
- **Eventual visibility**: a ticket created in region A and scanned in
  region B has a replication lag. Either pin the gate to a region or use
  global tables with `ConsistentRead = true`.
- **Capacity planning**: per-lot QPS budget, with auto-rebalancing of lots
  to runtime instances. Out of LLD scope.

---

## Q10. What would you change for an EV-charging spot subtype?

**TL;DR** — Add a second dimension to the bucket key: `(VehicleSize, Feature)`.

### The shape

```java
record SpotKey(VehicleSize size, EnumSet<Feature> features) {}

Map<SpotKey, Semaphore> freeByKey;
Map<SpotKey, List<ParkingSpot>> spotsByKey;
```

`Feature` = `{ EV_FAST, EV_SLOW, HANDICAPPED, COVERED, ... }`.

### Why an EnumSet, not separate maps?

Because a spot can be **both** `EV_FAST` and `HANDICAPPED`. An EnumSet is
4 bytes for ≤64 features and gives us O(1) intersection tests.

### Up-grade rule generalizes

```text
A request for { size: CAR, features: {EV_FAST} } is satisfied by any spot S
where  S.size >= CAR  AND  S.features ⊇ {EV_FAST}
```

The "subset" check replaces the simple "≥" we had on size alone. The
iteration order over candidates becomes a *partial* order — pick a topological
sort that prefers most-specific (matches only what's needed) first.

### What this teaches

It's a clean illustration of the rule: **once you have one independent axis
of constraint, the cost of adding a second axis is just widening the keys,
not redoing the locking**. That's the payoff of getting the concurrency right
the first time.

---

## Bonus — quickfire round

| Question | Answer (≤2 lines) |
|---|---|
| Why no `volatile` on `occupiedBy`? | Always read/written under the per-floor lock → happens-before is established by lock release/acquire. |
| Why is `Ticket` immutable? | It gets cached, printed, logged. Immutability removes a whole class of "the ticket Alice scanned ≠ the ticket the gate looked up" bugs. |
| What happens if `freeBySize` is initialized with wrong counts? | Either rejected vehicles when spots are free (count too low) or `IllegalStateException` at scan time (count too high). Build it from the spot list, never from config. |
| Is `tryAcquire` lock-free? | Yes — it's a CAS on `Sync.state`. The wait queue only engages on `acquire()` / `tryAcquire(timeout)`. |
| How would you implement priority parking (VIP first)? | Use `Semaphore(N, fair=false)` with two-tier waiters: VIP threads first hit a `Semaphore(VIP_RESERVED)`; on failure they fall through to the shared one. Don't rely on JVM thread priority — it's a hint, not a guarantee. |
| When is `synchronized` ok here? | Almost never. It has no `tryLock(timeout)`, no `tryLock()`, no fairness, no interruptibility. `ReentrantLock` is strictly more capable at the same cost. |
