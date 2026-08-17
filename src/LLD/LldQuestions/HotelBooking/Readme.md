# Hotel Booking — Basic LLD

A minimal, production-shaped Low-Level Design for a hotel reservation
system, focused on the **hardest** part of the problem: **concurrent
range-based reservation with idempotent retries**.

This is **project #5** in the [LLD concurrency curriculum](../README.md). It
is a near-clone of [`TicketBooking`](../TicketBooking/) — the lesson is:

> *"Once you have the 'lock a key, do a compound action' skeleton, harder
> conflict predicates (interval overlap vs set membership) slot in
> without changing the concurrency design."*

Read `TicketBooking` first.

---

## 1. Problem statement

Many users browse the same hotel at the same time and click "Reserve" on
overlapping date ranges for the same room. The system must guarantee:

| Invariant | Plain English |
|---|---|
| **No double-booking on overlap** | Two reservations on the same room must have disjoint date ranges. |
| **Adjacent ranges allowed** | [Jan 5, Jan 7) and [Jan 7, Jan 9) are NOT a conflict — guest A checks out the morning guest B checks in. |
| **Idempotency on retry** | A client retry with the same request-id returns the original reservation, not a new one. |
| **Cancel frees the range** | After cancellation, the cancelled range becomes bookable again. |
| **Per-room isolation** | Heavy traffic on Room 101 must not slow down Room 102. |

---

## 2. Architecture

```
                        ┌──────────────────────────────┐
                        │            Main              │
                        │  (4 race-condition demos)    │
                        └───────────────┬──────────────┘
                                        │
                                        ▼
                        ┌──────────────────────────────┐
                        │     HotelBookingService      │
                        │   per-room ReentrantLock     │
                        │   + idempotency map          │
                        └──────┬───────────┬───────────┘
                               │           │
              ┌────────────────┘           └────────────────┐
              ▼                                              ▼
   ┌──────────────────────┐                    ┌──────────────────────────┐
   │   RoomRepository     │                    │ ReservationRepository    │
   │  ConcurrentHashMap   │                    │  CHM: roomId -> List     │
   │                      │                    │  CHM: id -> Reservation  │
   └──────────────────────┘                    └──────────────────────────┘
```

The structural diff vs `TicketBooking`:

| Concept | TicketBooking | HotelBooking |
|---|---|---|
| Lock key | `showId` | `roomId` |
| Lock granularity | per show | per room |
| Conflict predicate | "any seat already booked?" — set membership | "any reservation overlaps?" — interval-overlap scan |
| Soft hold | TTL'd `SeatLock` (2-phase) | None in this basic version |
| Idempotency | Implicit via 2-phase | Explicit via `clientRequestId` |

---

## 3. Directory layout

```
HotelBooking/
├── Main.java                          # 4 concurrent demo scenarios
├── Readme.md                          # this file
├── Interview-QA.md                    # SDE-3 Q&A
├── models/
│   ├── Room.java                      # id + type + capacity
│   ├── RoomType.java                  # STANDARD | DELUXE | SUITE
│   ├── DateRange.java                 # ★ half-open interval + overlap test
│   ├── Reservation.java               # one room × one range × one user
│   └── ReservationStatus.java         # CONFIRMED | CANCELLED
├── service/
│   └── HotelBookingService.java       # ★ lock-key=room, predicate=overlap
├── repository/
│   ├── RoomRepository.java
│   └── ReservationRepository.java
└── exceptions/
    └── RoomUnavailableException.java
```

---

## 4. The half-open date convention

`[checkIn, checkOut)` — closed on check-in, **open** on check-out.

Why? Because in the real world, the morning of Jan 14 is *both* "guest A's
check-out day" and "guest B's check-in day". Half-open math gets this right:

```text
A.dates = [Jan 12, Jan 14)
B.dates = [Jan 14, Jan 16)

A.overlaps(B)  ↔  A.checkIn < B.checkOut  AND  B.checkIn < A.checkOut
              ↔  Jan 12 < Jan 16          AND  Jan 14 < Jan 14
              ↔  true                     AND  false
              ↔  false                    ✓
```

Adjacent ranges are not conflicts. Closed-closed ranges (`[..., Jan 14]`)
would falsely conflict.

---

## 5. Concurrency primitives, in the order they matter

### 5a. Per-room fair `ReentrantLock`

The compound action `(scan reservations for overlap + write a new
reservation)` must be atomic. Two threads checking before either writes
could both believe their range is free and both write.

Granularity is per-room — the same trick as `TicketBooking` per-show.
Different rooms run fully in parallel.

### 5b. The interval-overlap conflict scan

```java
for (Reservation r : reservationRepository.getAllForRoom(roomId)) {
    if (!r.isActive()) continue;          // cancelled doesn't count
    if (r.getDates().overlaps(dates)) {
        throw new RoomUnavailableException(...);
    }
}
```

This is O(reservations on this room). For a real hotel that's a few
hundred per room per year — fast enough that we don't bother with an
interval tree. For a chain with millions of rooms, you'd push this into
the DB with `tstzrange && tstzrange` (PostgreSQL's `&&` is the overlap
operator on range types).

### 5c. `ConcurrentHashMap<String, ReentrantLock>` for locks

`computeIfAbsent` on the CHM is atomic, so racing callers on a brand-new
roomId still see the same lock instance. Same pattern as `TicketBooking`.

### 5d. Idempotency table — also a CHM

```java
Map<String, String> idempotency;   // clientRequestId -> reservationId
```

A retry checks this map *before* taking the lock (fast path) and *again
inside* the lock (double-check). The double-check is the key correctness
move: between the first read and acquiring the lock, another thread with
the same `clientRequestId` may have completed the booking. The inside-lock
re-check picks that up.

### 5e. `volatile ReservationStatus`

Reads in diagnostics paths (e.g. "is this reservation still active?") may
not hold the per-room lock. `volatile` gives the happens-before so they
see the latest status. Writes (cancel) DO hold the lock.

---

## 6. The two reads, side by side

There are two reads of `idempotency` in `reserve()` and that surprises
some people. They are intentionally separate.

| Read | Where | Why |
|---|---|---|
| First | *Before* taking the lock | Fast path: most retries are dedupable without ever entering the critical section |
| Second | *Inside* the lock | Correctness: covers the race where two retries arrive in the gap between read-1 and lock |

This is the **canonical "double-check after lock" pattern** for any
optimistic-then-pessimistic deduplication. Same shape used in
`AtmTransfer` (project #6) and in standard JDK code (`Singleton` /
`ConcurrentHashMap.computeIfAbsent`).

---

## 7. Demo (`Main.java`)

Four scenarios.

### Scenario 1 — Thundering herd

50 threads race for the SAME room over the SAME range. Exactly ONE must
win; 49 must be rejected.

```
=== Scenario 1: 50 users race for Room 101, Dec 12-14 ===
  WINNER u11 -> 2b4c6be0-...
  winners=1 losers=49
```

### Scenario 2 — Adjacent ranges

Same room. Range A = [Jan 5, Jan 7). Range B = [Jan 7, Jan 9). Both must
succeed because of half-open semantics.

```
=== Scenario 2: adjacent ranges on the same room both succeed ===
  alice booked [2027-01-05, 2027-01-07)
  bob   booked [2027-01-07, 2027-01-09)  (same room, no overlap)
```

### Scenario 3 — Idempotency

Two `reserve()` calls with the same `clientRequestId` return the same
reservation id (the first one). No double-booking.

```
=== Scenario 3: same request id twice -> ONE reservation ===
  first  id=11ecf031-...
  retry  id=11ecf031-...  (must equal first)
```

### Scenario 4 — Cancel and rebook

Confirm the cancellation actually frees the range.

```
=== Scenario 4: cancel -> the range becomes bookable again ===
  dave reserved [2027-03-10, 2027-03-14) on R301
  erin rejected (as expected): Room R301 unavailable for ...
  dave cancelled
  erin now reserved [2027-03-10, 2027-03-14) on R301
```

---

## 8. How to run

```bash
cd bel-20
javac -d /tmp/hb-build $(find LLD/LldQuestions/HotelBooking -name '*.java')
java -cp /tmp/hb-build LLD.LldQuestions.HotelBooking.Main
```

---

## 9. What was deliberately left out

| Skipped | Why fine for basic LLD | What to add for production |
|---|---|---|
| Two-phase soft-hold | Atomic booking is fine when payment is sync | Plug in TicketBooking's `SeatLockProvider` keyed by `(roomId, dateRange)` |
| Dynamic pricing | Flat price per night | Strategy: weekend / demand / loyalty modifiers, like TicketBooking's `PricingStrategy` |
| Multi-room reservations | Single-room per request | Lock multiple rooms in **sorted-by-id order** (AtmTransfer's lesson) to avoid deadlock |
| Search by amenity / floor | One predicate (type) | Add a `Specification` pattern; combine predicates with AND/OR |
| Persistence | In-memory | DB unique constraint on (room_id, date_overlap) — PostgreSQL has `EXCLUDE USING gist (room_id WITH =, tstzrange(...) WITH &&)` |
| Overbooking | Reject conflicts | Hotels DO overbook; track `overbooked_count`, page management when it crosses |
| Notifications | None | Emit `ReservationCreated` event → email/SMS adapter |
| Multi-region | Single JVM | Route by hotel-id; per-hotel DB; eventual sync for global search |

---

## 10. Interview-ready one-liner

> Per-room fair `ReentrantLock` makes the compound `(scan-for-overlap +
> write-reservation)` action atomic; half-open `[checkIn, checkOut)` date
> ranges give the correct "adjacent is not conflict" semantics; a
> `ConcurrentHashMap` of `clientRequestId -> reservationId` plus a
> double-check inside the lock gives at-most-once on retry. Different rooms
> are fully independent because the lock key is `roomId`. The structural
> design is identical to `TicketBooking` — only the conflict predicate
> changes (interval-overlap vs set-membership), which is exactly the lesson.
