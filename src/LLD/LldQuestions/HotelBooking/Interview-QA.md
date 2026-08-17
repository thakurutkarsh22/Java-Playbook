# Hotel Booking — Interview Q&A Cheat Sheet

Companion to `Readme.md`. The Readme explains the *design*; this file
captures the SDE-3-level questions an interviewer will actually ask. This
project is deliberately a near-clone of `TicketBooking`, so most answers
focus on the **diffs**: range-overlap predicate, idempotency, and the
question of whether to add a TTL'd soft hold.

---

## Q1. Why is the conflict predicate "interval overlap" and not "date in set"?

**TL;DR** — Because the conflict unit is the *range*, not the night.
Checking each night individually would be O(nights × reservations); the
overlap test is O(1) per existing reservation.

### The "set of nights" alternative

You could model state as `Map<roomId, Set<LocalDate>> bookedNights`. Then
a conflict check is:

```java
for (LocalDate d = dates.checkIn; d.isBefore(dates.checkOut); d = d.plusDays(1)) {
    if (bookedNights.get(roomId).contains(d)) return CONFLICT;
}
```

That works! And it's O(nights). Two issues:

1. **Memory**: every night booked stores one date. For a 365-day-busy
   room that's 365 entries instead of, say, 50 reservations.
2. **History fidelity**: we lose "who booked which range". A cancellation
   for a 5-night booking would need to know to delete exactly those 5
   dates. With reservation objects we just flip `status = CANCELLED`.

### The interval-overlap approach

```java
for (Reservation r : reservations) {
    if (r.isActive() && r.dates.overlaps(requested)) return CONFLICT;
}
```

O(reservations on this room). Compact, preserves history, supports cancel.
The runtime is dominated by reservations not nights, which is the right
asymptotic for a hotel.

### When does the set-of-nights variant win?

When you need O(1) "is this date booked?" queries for an availability
heatmap or calendar UI. In that case keep BOTH: reservations are the
source of truth, the date-set is a derived index.

---

## Q2. Walk me through the half-open convention. Why `[checkIn, checkOut)`?

**TL;DR** — Because real hotels share the checkout day. Closed-closed
ranges produce a false conflict on the turn-over day.

### The bug with closed-closed

```text
A.dates = [Jan 12, Jan 14]    // includes Jan 14
B.dates = [Jan 14, Jan 16]    // includes Jan 14

overlap?  Jan 14 ∈ A  AND  Jan 14 ∈ B  -> conflict
```

But in reality, A vacates the morning of Jan 14 and B arrives the
afternoon of Jan 14. Same room, no conflict. The conflict is an artifact
of including the boundary in both.

### The half-open fix

```text
A.dates = [Jan 12, Jan 14)    // includes Jan 12, 13. NOT Jan 14.
B.dates = [Jan 14, Jan 16)    // includes Jan 14, 15.

overlap?  A.checkIn < B.checkOut  AND  B.checkIn < A.checkOut
       =  Jan 12 < Jan 16        AND  Jan 14 < Jan 14
       =  true                    AND  false
       =  false                   ✓
```

### Why this is universal

Industry conventions:

- Hotels: half-open by night. ✓
- Calendars (Google, Outlook): half-open by minute. A 10:00-11:00 event
  ends before 11:00. ✓
- DB ranges (PostgreSQL `[a, b)`, Python `timedelta`): half-open. ✓

Closed-closed is a beginner trap. Always use half-open for time ranges.

---

## Q3. Walk me through the idempotency double-check. Why two reads?

**TL;DR** — One read before the lock for speed; one inside the lock for
correctness. This is the canonical "optimistic-then-pessimistic dedup"
pattern.

### The race we're closing

```text
Thread A reserve(idempotency="k1", dates=D1)        Thread B reserve(idempotency="k1", dates=D1) — retry of A
  read idempotency.get("k1")   -> null
                                                       read idempotency.get("k1")  -> null
  acquire roomLock
  scan reservations (no overlap)
  create reservation R1
  idempotency.put("k1", R1.id)
  release lock
                                                       acquire roomLock           (now)
                                                       scan reservations          (sees R1!)
                                                       overlap with R1.dates      -> THROW UNAVAILABLE !!!
```

That's wrong. We told the client "unavailable" when the *correct* response
was "here is your reservation again". The fix is: inside the lock, **re-read
idempotency** and short-circuit return.

### The fixed code

```java
roomLock.lock();
try {
    if (clientRequestId != null) {
        String existing = idempotency.get(clientRequestId);   // ← double-check
        if (existing != null) {
            return reservationRepository.get(existing);
        }
    }
    // ... overlap scan + create ...
}
```

Now the retry sees R1 via idempotency and returns it, never reaching the
overlap scan.

### Why not just one read inside the lock?

You could! And it would be correct. The outside-lock pre-check is a
**latency optimization**: most retries are dedupable without ever entering
the critical section. For high-retry workloads (mobile clients, flaky
networks) the pre-check is the difference between O(retries) lock
acquisitions and O(unique requests).

---

## Q4. What if Alice and Bob both retry with the same `clientRequestId`?

**TL;DR** — That's a malformed request. We *do* deduplicate, but we don't
validate that the user matches. Server-side auth must validate ownership
separately.

### What our code does

```java
String existing = idempotency.get(clientRequestId);
if (existing != null) return reservationRepository.get(existing);
```

The retry returns Alice's reservation to Bob if Bob reused Alice's
`clientRequestId`. That's a data-leak class of bug.

### What real systems do

Make the idempotency key scoped: `idempotency.put((userId, clientRequestId),
reservationId)` — then Alice and Bob having the same key is fine, the
tuple differs.

Or validate inside the lookup:

```java
Reservation existing = reservationRepository.get(idempotency.get(key));
if (existing != null && !existing.getUserId().equals(userId)) {
    throw new SecurityException("idempotency key collision across users");
}
```

For the LLD we keep the table simple; in interview, *mention this* before
the bar-raiser does.

---

## Q5. How would you extend this to a two-phase soft hold (like TicketBooking)?

**TL;DR** — Add a `SeatLockProvider`-style hold service keyed by
`(roomId, DateRange)`. Conflict check walks both committed reservations
AND active holds.

### Shape

```java
interface RangeHoldProvider {
    boolean lock(String roomId, DateRange dates, String userId, Duration ttl);
    boolean validate(String roomId, DateRange dates, String userId);
    void release(String roomId, DateRange dates, String userId);
}
```

Hold storage: `Map<roomId, List<HeldRange>>` with `HeldRange` carrying
expiry. Like `SeatLockProvider` but the *key* is an interval, not a seat
id.

### Modified reserve flow

```text
initiateReservation:
  acquire roomLock
  for r in committed: if r.active && r.dates overlaps requested -> CONFLICT
  for h in holds:     if !h.expired && h.dates overlaps requested && h.user != us -> CONFLICT
  holds.add(HeldRange(roomId, requested, user, expiresAt))
  release roomLock
  return pendingBookingId

confirmReservation:
  acquire roomLock
  validate hold still alive & owned
  for r in committed: re-check overlap (paranoid)
  create Reservation
  remove hold
  release roomLock
```

### Why pluggable?

So you can swap the in-memory holder for a Redis-backed one without
touching the service. Redis Lua: read the holds zset, check overlap with
client-supplied tstzrange, ZADD with expiry. Same shape.

---

## Q6. What's the worst-case scan length on `reserve()`?

**TL;DR** — `O(reservations on that room, ever)`. Bounded in practice by
how long you keep history.

### What dominates

Cancelled reservations still sit in the list — we skip them, but we
*iterate* them. If a room sees 1000 reservations a year and you keep 5
years of history, that's a 5000-entry scan per reservation attempt.

### Mitigations, in order of effort

1. **Move history out**: keep `byRoom` only for *active* reservations;
   archive cancelled / past ones to `historyRepository`. O(active) scans.
2. **Sort by checkOut and break early**: maintain `byRoom` sorted by
   `checkOut DESC`. Stop the scan when `r.checkOut <= requested.checkIn`
   — there's no overlap possible.
3. **Interval tree**: O(log n) scan, supports overlap queries in one walk.
   Worth it past ~10k active per room.
4. **Database GIST index**: PostgreSQL `EXCLUDE USING gist (room_id WITH
   =, daterange WITH &&)` enforces both correctness AND O(log n) lookup.
   The DB *is* the source of truth in production.

For an LLD interview answer, name all four, default to #1+#2 for the
in-memory version, mention #4 as the production answer.

---

## Q7. What changes if a single reservation can span multiple rooms?

**TL;DR** — You need to lock multiple rooms. Lock them in **sorted-by-id
order** to avoid deadlock. This is the lesson of `AtmTransfer` (project
#6).

### The naive (wrong) code

```java
reserveMulti(List<String> roomIds, DateRange dates) {
    for (String r : roomIds) {
        lockFor(r).lock();
    }
    // ... overlap check & write each room ...
    for (String r : roomIds) {
        lockFor(r).unlock();
    }
}
```

This deadlocks if thread A asks for `[R1, R2]` and thread B asks for
`[R2, R1]`. A locks R1, B locks R2, A waits on R2, B waits on R1 → cycle.

### The fix

```java
List<String> sorted = new ArrayList<>(roomIds);
Collections.sort(sorted);                        // canonical order
for (String r : sorted) {
    lockFor(r).lock();
}
```

All threads acquire in the same global order → no cycle → no deadlock.

This is the ATM transfer lesson. Read project #6 for the full treatment.

---

## Q8. Could you replace `ConcurrentHashMap<String, ReentrantLock>` with `Striped`?

**TL;DR** — Yes for very large rooms count. Striped trades correctness
for bounded memory at the cost of false sharing across keys.

### Guava `Striped`

```java
Striped<Lock> stripes = Striped.lazyWeakLock(1024);
Lock lock = stripes.get(roomId);
```

Internally hashes `roomId` to one of 1024 locks. Hot key gets its own
stripe most of the time, but two keys that collide on the hash share the
lock — even though they're unrelated.

### Trade-off

| Approach | Memory | Hot-key fairness | False sharing |
|---|---|---|---|
| `ConcurrentHashMap<String, ReentrantLock>` | O(unique keys ever seen) | Perfect — each key has its own lock | None |
| `Striped<Lock>` | O(stripe count) — bounded | Same as CHM if no hash collision | Possible: two unrelated keys may contend |
| `Striped.lazyWeakLock` | Smaller — locks created on demand, GC'd when no callers reference | Same | Same |

For a hotel chain with 10k rooms: CHM is fine (10k tiny ReentrantLocks =
~few MB). For 10M tenants in a SaaS: Striped is the right move.

### What I'd say in interview

"For this LLD I'd use CHM because the keyspace is small. If we expanded
to a multi-tenant SaaS where the keyspace is unbounded, I'd swap to
`Striped.lazyWeakLock(N)` with N tuned to expected contention and accept
the false-sharing cost. The service code doesn't change — only the lock
factory."

---

## Q9. How do you implement "find me any available DELUXE room for Jan 15-17"?

**TL;DR** — Linear scan over rooms of the right type, with each room's
lock acquired briefly for the overlap test. For larger hotels, maintain
a precomputed `available[date]` set.

### The simple impl

```java
findAvailable(RoomType type, DateRange dates) {
    for (Room room : rooms.all()) {
        if (room.type != type) continue;
        lockFor(room.id).lock();
        try {
            if (noOverlap(room, dates)) return room;
        } finally {
            lockFor(room.id).unlock();
        }
    }
    return null;
}
```

O(rooms × reservations-per-room). For a 200-room hotel with 100
reservations/room/year, this is 20k overlap checks per search. ~10 ms.
Fine.

### Why is this not a problem for booking-rate?

Because **search is read-only** and our per-room locks are held for µs.
Searches don't block bookings on other rooms. They DO briefly block a
concurrent booking on the room being inspected — by design, because the
search wants a consistent answer.

### What changes for 10k rooms?

Two options:

1. **Per-date availability index**: `Map<LocalDate, Set<roomId>> available`
   maintained on reservation create/cancel. Search = set intersection
   over the requested dates. O(nights × rooms-available).
2. **Search service** decoupled from booking service: search reads a
   denormalized read-model with eventual consistency. Booking writes
   propagate to the search index via events.

Real-world: Airbnb / Booking.com run their search on Elasticsearch /
Solr fed by CDC events from the booking DB. Same pattern.

---

## Q10. How do you migrate the in-memory locks to a distributed lock?

**TL;DR** — Replace `ReentrantLock` with a `DistributedLock` interface;
the service code doesn't change.

### Interface

```java
interface DistributedLock {
    boolean tryAcquire(String key, Duration ttl);
    void release(String key);
}
```

### Redis impl

```
SET lock:room:R101 <client-id> NX PX 30000     -- atomic acquire with ttl
```

`NX` = only if not exists. `PX` = ms TTL. Returns OK or nil. Release is a
Lua script that checks the client-id matches before deleting.

### The two new failure modes

1. **TTL expiry mid-critical-section**: another client may acquire while
   we still think we own it. Mitigate by setting TTL much greater than
   the longest valid critical section. For belt-and-suspenders, use a
   "fencing token" (Redlock / Zookeeper) that the storage layer
   validates on write.
2. **Lock held forever after client crash**: TTL solves this — the lock
   auto-frees after TTL. Worst case, one extra wait period for the next
   acquirer.

### What does NOT change

The *service-level* reasoning. We still hold the lock around (check
overlap + write reservation). We still double-check idempotency inside.
The lock type abstracts the storage; the algorithm is identical.

---

## Bonus — quickfire round

| Question | Answer (≤2 lines) |
|---|---|
| Why is `DateRange` immutable? | It's the cache key for idempotency and the source-of-truth for conflict checks. Mutability there would silently corrupt the conflict scan. |
| Why `volatile ReservationStatus`? | One-way write under the lock; many lockless reads in diagnostics. Volatile gives the happens-before without needing the lock. |
| What does `overlaps` return for exact equality `[a, b)`? | True — they share every interior point. |
| What does `overlaps` return for `[a, b)` and `[b, c)`? | False — `a < c` is true but `b < b` is false. Adjacent ⇒ no overlap. |
| Could you use `synchronized` here? | Yes for the trivial case, but `ReentrantLock(true)` is strictly more capable: fairness, `tryLock(timeout)`, interruptibility. |
| What if the JVM dies between `idempotency.put` and `reservationRepository.save`? | In-memory we lose both. In a real DB, both writes are in one transaction so neither survives — the retry correctly recreates. The order in our code (save first, idempotency second) is just for visibility ordering. |
| When is the soft-hold extension worth it? | When payment is OUT-OF-PROCESS and slow (>1s). For a sync booking flow, atomic is simpler and equivalent. |
| What about overbooking? (Hotels actually do.) | Reframe: invariant becomes "active reservations ≤ overbookingLimit". The conflict check counts overlapping reservations instead of bool-returning — same lock structure. |
