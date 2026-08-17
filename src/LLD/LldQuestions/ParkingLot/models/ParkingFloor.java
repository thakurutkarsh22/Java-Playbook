package LLD.LldQuestions.ParkingLot.models;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/*
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║                            ParkingFloor                                ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * Concurrency model -- two primitives, two different jobs.
 *
 * 1) Per-vehicle-size SEMAPHORE (`freeBySize`)
 *    Semantically a *count* of how many spots of each size are currently free.
 *    Cheap, branch-free, lock-free. The 99% case -- "is there any CAR spot
 *    left?" -- becomes a `tryAcquire` with zero map traversal.
 *
 *    Why a Semaphore and not an AtomicInteger?
 *      - We need *blocking* semantics with a timeout (`tryAcquire(t, unit)`),
 *        so a polite caller can wait up to a budget instead of busy-looping.
 *      - Semaphore is the textbook counting primitive for "N tokens, drain
 *        and refill from many threads". AtomicInteger would force us to
 *        re-implement the wait queue, badly.
 *
 * 2) Per-floor `ReentrantLock` (`floorLock`)
 *    Held only while we *find a specific spot* and flip its `occupiedBy`.
 *    Tiny critical section (microseconds): scan the per-size list until we
 *    hit the first free spot, set `occupiedBy`, return.
 *
 *    Different floors are fully independent -- floor 1 traffic does not block
 *    floor 2 traffic. This is the "shard by key" pattern again.
 *
 * Why is the two-primitive design correct?
 *
 *   tryAcquire(size)        ── decrements the counter, atomically.
 *   floorLock.lock()        ── now we *promise* to find a spot that exists.
 *   scan list for free spot ── must succeed (counter said >=1 was free).
 *   spot.occupy(plate)      ── mutate inside the lock.
 *   floorLock.unlock()
 *
 *   The semaphore is the *admission control*: anyone who passes it has been
 *   pre-authorized to take one spot. The lock then resolves *which* spot.
 *
 *   On release: floorLock.lock(); spot.release(); floorLock.unlock();
 *   THEN semaphore.release(size). The order matters -- we must finish
 *   mutating before we tell other threads "a spot is free", otherwise a fast
 *   thread that enters via tryAcquire could see the not-yet-released spot.
 *
 * Worked example with 3 CAR spots, 4 cars racing
 * ──────────────────────────────────────────────
 *   t=0     freeBySize[CAR] = 3
 *   t=1     u1 tryAcquire(CAR) -> permits 3→2, locks floor, takes C1, unlocks
 *   t=1.01  u2 tryAcquire(CAR) -> permits 2→1, locks floor, takes C2, unlocks
 *   t=1.02  u3 tryAcquire(CAR) -> permits 1→0, locks floor, takes C3, unlocks
 *   t=1.03  u4 tryAcquire(CAR) -> permits 0, FAILS without ever taking the floor lock.
 *
 * The semaphore short-circuits the "no space" case so it never even contends
 * for the floor lock. Hot lot, no problem.
 */
public class ParkingFloor {

    private final String id;
    private final List<ParkingSpot> spots;

    /*
     * Lookup table: size -> list of spots of that exact size. Built once at
     * construction; immutable thereafter. Doing the bucketization up front
     * means the per-park scan is O(spots-of-this-size) not O(all-spots).
     */
    private final Map<VehicleSize, List<ParkingSpot>> spotsBySize =
            new EnumMap<>(VehicleSize.class);

    /*
     * Permits per size. Built from spotsBySize at construction.
     *
     * NON-fair on purpose: parking is not "first to arrive" -- the gate is
     * already sequential, and on contention we want *throughput*. Compare to
     * TicketBooking where fairness IS the user expectation; here it isn't.
     */
    private final Map<VehicleSize, Semaphore> freeBySize =
            new EnumMap<>(VehicleSize.class);

    /*
     * Coarse per-floor lock. Held for microseconds: scan + flip occupiedBy.
     * If profiling later showed this is the bottleneck, the next step is a
     * lock-striped variant (one lock per row), not a finer-grained one per
     * spot -- per-spot would be more memory than spots themselves.
     */
    private final ReentrantLock floorLock = new ReentrantLock();

    public ParkingFloor(String id, List<ParkingSpot> spots) {
        this.id = id;
        this.spots = spots;

        for (VehicleSize size : VehicleSize.values()) {
            spotsBySize.put(size, new ArrayList<>());
        }
        for (ParkingSpot spot : spots) {
            spotsBySize.get(spot.getSize()).add(spot);
        }
        for (VehicleSize size : VehicleSize.values()) {
            freeBySize.put(size, new Semaphore(spotsBySize.get(size).size()));
        }
    }

    public String getId() {
        return id;
    }

    public List<ParkingSpot> getSpots() {
        return spots;
    }

    /*
     * Reserve + claim one spot atomically. Returns null if no spot of an
     * acceptable size is free. May up-grade (BIKE -> CAR spot -> TRUCK spot)
     * when the requested size is exhausted.
     *
     * Strategy
     * ────────
     * 1. Walk sizes in ASCENDING order starting from the vehicle's own size.
     *    A BIKE first tries BIKE permits, then CAR, then TRUCK. We try
     *    smaller-first because the smallest acceptable spot is also the
     *    cheapest one (a TRUCK spot occupied by a BIKE is a *waste*).
     * 2. For each size, attempt tryAcquire(permit). On success we hold one
     *    "promise" that a spot of that exact size exists.
     * 3. Take the floor lock and find a free spot of that size. We are
     *    GUARANTEED to find one because the permit count said >=1 was free
     *    AND only the holder of the permit can take that spot.
     */
    public ParkingSpot reserveAndClaim(Vehicle vehicle) {
        VehicleSize requested = vehicle.getSize();

        for (VehicleSize candidate : VehicleSize.values()) {
            if (candidate.compareTo(requested) < 0) {
                continue;                       // candidate too small, skip
            }
            Semaphore permits = freeBySize.get(candidate);
            if (!permits.tryAcquire()) {
                continue;                       // none of this size left, escalate
            }
            // we now own one permit for `candidate`; must consume or release it
            try {
                floorLock.lock();
                try {
                    for (ParkingSpot spot : spotsBySize.get(candidate)) {
                        if (spot.isFree()) {
                            spot.occupy(vehicle.getPlate());
                            return spot;
                        }
                    }
                } finally {
                    floorLock.unlock();
                }
                // Defensive: we should never reach here -- permit guaranteed a
                // free spot. If we do, the spot list is desynchronized from the
                // semaphore, which is a bug. Restore the permit to keep the
                // invariant `permits == (count of free spots of this size)`.
                permits.release();
                throw new IllegalStateException(
                        "permit/spot desync on floor " + id + " size " + candidate);
            } catch (RuntimeException e) {
                throw e;
            }
        }
        return null;                            // every acceptable size was full
    }

    /*
     * Free a spot held by the given plate. Order matters: we must unset
     * occupiedBy under the lock BEFORE we release the permit, so that a
     * thread waking on the permit sees the spot as free.
     */
    public void release(ParkingSpot spot, String plate) {
        VehicleSize size = spot.getSize();
        floorLock.lock();
        try {
            // Ownership check: defends against a buggy gate releasing the
            // wrong spot. Silently ignoring is safer than throwing because
            // the caller is usually a finally{} block.
            if (spot.getOccupiedBy() == null || !spot.getOccupiedBy().equals(plate)) {
                return;
            }
            spot.release();
        } finally {
            floorLock.unlock();
        }
        freeBySize.get(size).release();
    }

    /*
     * Read-only snapshot of free counts for status displays / metrics.
     * Returns the semaphore's available-permits count, which is a *consistent*
     * lower bound (it never reports more free than there actually are).
     */
    public int availablePermits(VehicleSize size) {
        return freeBySize.get(size).availablePermits();
    }
}
