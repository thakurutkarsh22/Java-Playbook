# Parking Lot — Basic LLD

A minimal, production-shaped Low-Level Design for a multi-floor parking lot
with mixed vehicle sizes. The focus is correctly handling the **hardest** part
of the problem: **concurrent vehicle admission under a finite, sharded
resource**.

This is **project #1** in the [LLD concurrency curriculum](../README.md) and
is the canonical worked example for the pattern:

> *"Use a counting `Semaphore` for admission control, and a small
> `ReentrantLock` for the compound action that follows."*

---

## 1. Problem statement

The lot has multiple floors. Each floor has a mix of BIKE / CAR / TRUCK spots.
Vehicles arrive at the entry gate from many lanes simultaneously. The system
must guarantee:

| Invariant | Plain English |
|---|---|
| **No double-occupancy** | A confirmed spot is parked by exactly one vehicle. |
| **No false rejection** | If any acceptable spot is genuinely free, an admitted vehicle finds it. |
| **No false admission** | If the lot has zero acceptable spots free, the vehicle is rejected — never *parked into nothing*. |
| **Up-grade rule** | A smaller vehicle may take a larger spot when its own size is exhausted, but never the reverse. |
| **Per-floor isolation** | Floor 1 traffic must not slow down floor 2 traffic. |

---

## 2. Architecture

```
                        ┌──────────────────────────────┐
                        │            Main              │
                        │  (3 race-condition demos)    │
                        └───────────────┬──────────────┘
                                        │
                                        ▼
                        ┌──────────────────────────────┐
                        │     ParkingLotService        │
                        │ (lock-FREE; pure routing)    │
                        └──────┬───────────┬───────────┘
                               │           │
              ┌────────────────┘           └────────────────┐
              ▼                                              ▼
   ┌──────────────────────┐                    ┌──────────────────────────┐
   │  ParkingFloor (×N)   │                    │   TicketRepository       │
   │  - per-floor lock    │                    │   ConcurrentHashMap      │
   │  - Semaphore[size]   │                    │                          │
   │  - List<Spot>        │                    └──────────────────────────┘
   └──────────────────────┘
```

Note the **service has no lock of its own**. All synchronization is *inside*
`ParkingFloor`. This is the right split because there is no compound action
that spans floors — `park()` picks a floor and delegates, `unpark()` is keyed
by ticket which already names the floor.

---

## 3. Directory layout

```
ParkingLot/
├── Main.java                          # 3 concurrent demo scenarios
├── Readme.md                          # this file
├── Interview-QA.md                    # SDE-3 Q&A
├── models/
│   ├── VehicleSize.java               # BIKE | CAR | TRUCK (ordered)
│   ├── Vehicle.java                   # plate + size
│   ├── ParkingSpot.java               # id + size + occupiedBy
│   ├── ParkingFloor.java              # ★ holds the lock + per-size semaphores
│   └── Ticket.java                    # immutable receipt
├── service/
│   └── ParkingLotService.java         # routes park/unpark to floors
├── repository/
│   └── TicketRepository.java          # CHM-backed ticket store
└── exceptions/
    ├── LotFullException.java
    └── InvalidTicketException.java
```

---

## 4. Concurrency primitives, in the order they matter

### 4a. Per-vehicle-size `Semaphore` on each floor

This is the **headline primitive**. A `Semaphore(N)` holds N permits. Calling
`tryAcquire()` decrements; `release()` increments. We keep one Semaphore per
`VehicleSize` per floor:

```text
Floor 1:
  freeBySize = {
    BIKE  -> Semaphore(4),
    CAR   -> Semaphore(5),
    TRUCK -> Semaphore(1)
  }
```

The fast path for "is there any CAR spot left on floor 1?" becomes a single
atomic CAS-style decrement. **No map traversal, no lock contention.**

Why `Semaphore` and not `AtomicInteger`? Because we want a *blocking* variant
later (`tryAcquire(timeout)` for "wait up to 5 seconds for a spot"), which
Semaphore gives us for free. An AtomicInteger would force us to reimplement
the wait queue ourselves — badly.

Why **non-fair**? Parking is not "first to arrive" — the entry gate is already
sequential. On contention we want throughput, not arrival ordering. Compare
to `TicketBooking` where users *do* expect first-clicker-wins; here, no.

### 4b. Per-floor `ReentrantLock` (`floorLock`)

Held only while we *find a specific free spot* in the per-size bucket and
flip its `occupiedBy`. Critical section is microseconds — scan + mutate.

Why a lock at all if the Semaphore already counts? Because the Semaphore
counts but doesn't *select*. We still need to find **which** of the
matching-size spots is free. A small lock around the scan is the simplest
correct answer.

### 4c. The semaphore-then-lock ordering

```text
tryAcquire(size)            ── "I am pre-authorized to take 1 spot of this size"
floorLock.lock()            ── now find which physical spot it is
scan spotsBySize for free   ── guaranteed to exist (permit promised so)
spot.occupy(plate)
floorLock.unlock()
```

On release, the order **reverses**:

```text
floorLock.lock()
spot.release()              ── flip occupiedBy to null FIRST
floorLock.unlock()
semaphore.release(size)     ── THEN signal "a spot is free"
```

Why this exact order? Because if we signaled the semaphore before the spot
was actually free, a fast thread that wakes on the permit could enter the
critical section and find no free spot — violating the "permit ⇒ free spot"
invariant.

### 4d. Up-grade by ascending size

```java
for (VehicleSize candidate : VehicleSize.values()) {
    if (candidate.compareTo(requested) < 0) continue;      // too small
    if (freeBySize.get(candidate).tryAcquire()) { ... }
}
```

We try smaller-first because a TRUCK spot occupied by a BIKE is *waste* — we
want to keep big spots free for the big vehicles that need them. This is the
"best-fit" allocator pattern, ported to a parking lot.

### 4e. Sharding by floor

Each floor has its **own** lock and its **own** semaphores. Floor 1 traffic
cannot block floor 2 traffic. This is the same sharding pattern as
`TicketBooking`'s per-show lock, generalized to a *fixed* set of shards.

---

## 5. The two-primitive split, side by side

Two primitives, two jobs. They are intentionally separate.

| Primitive | Lives in | Protects | Why |
|---|---|---|---|
| `Semaphore freeBySize[size]` | `ParkingFloor` | the *count* of free spots of a given size | so admission control is lock-free in the common case |
| `ReentrantLock floorLock` | `ParkingFloor` | the *selection* of which free spot to occupy + its `occupiedBy` flip | so the scan-and-mutate is atomic |

Keeping them separate means a floor that is full for one size (CAR exhausted)
still admits other sizes (BIKE / TRUCK) without ever entering the lock —
because the failed `tryAcquire` short-circuits and never even touches the
lock.

---

## 6. Demo (`Main.java`)

The demo runs **three scenarios**, each verifying a key invariant.

### Scenario 1 — Thundering herd

50 threads race to park CAR vehicles into a lot with exactly 10 CAR spots.
Exactly 10 must succeed; 40 must hit `LotFullException`. The demo asserts
this and aborts otherwise.

```
=== Scenario 1: 50 cars race; only 10 CAR spots exist ===
  parked=10 rejected=40
```

### Scenario 2 — Up-grade

After every BIKE spot is filled, the 9th bike must still find parking by
escalating into a CAR (or TRUCK) spot.

```
=== Scenario 2: bike up-grade when bike spots are full ===
  9th bike parked in spot=F1-C1 (must be a CAR or TRUCK spot)
```

### Scenario 3 — Release returns the permit

After all CAR spots fill, releasing one ticket must immediately let the next
incoming CAR park into exactly that spot. Proves that the
`spot.release() ; semaphore.release()` ordering preserves the invariant.

```
=== Scenario 3: release returns the permit so the next park succeeds ===
  released F1-C1 -> replacement parked at F1-C1
```

---

## 7. How to run

```bash
cd bel-20
javac -d /tmp/pl-build $(find LLD/LldQuestions/ParkingLot -name '*.java')
java -cp /tmp/pl-build LLD.LldQuestions.ParkingLot.Main
```

---

## 8. What was deliberately left out

| Skipped | Why fine for basic LLD | What to add for production |
|---|---|---|
| Payment / pricing | Out of concurrency scope | A `PricingStrategy` (size × duration × tariff) like in TicketBooking |
| Floor selection policy | Naive "first floor that fits" | Round-robin or priority queue keyed by free-count for even wear |
| Reservations | Walk-in only | Two-phase soft-hold + TTL — same shape as TicketBooking |
| Persistence | In-memory repos | JDBC or DynamoDB conditional writes for the tickets table |
| Multi-region | Single JVM | Per-lot Redis cluster; permits become `INCR`/`DECR` with a Lua atomic check |
| EV charging | Different spot subtype | Add `ChargingSpot extends ParkingSpot`; a 2nd dimension to the Semaphore map |

---

## 9. Interview-ready one-liner

> A counting `Semaphore` per (floor, vehicle-size) handles admission control
> in O(1) and lock-free; a small per-floor `ReentrantLock` makes the
> "find a free spot, mark it occupied" compound action atomic. Floors are
> independent shards, so different floors never block each other. The
> semaphore-then-lock acquisition order combined with the lock-then-semaphore
> release order is what preserves the invariant "permit available ⇒ a spot
> of that size is genuinely free."
