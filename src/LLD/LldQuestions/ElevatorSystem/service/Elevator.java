package LLD.LldQuestions.ElevatorSystem.service;

import LLD.LldQuestions.ElevatorSystem.models.Direction;
import LLD.LldQuestions.ElevatorSystem.models.Request;

import java.util.NavigableSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║                              Elevator                                  ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * A single elevator car, modeled as an ACTOR.
 *
 *   - One thread (the "drive loop") owns the entire mutable state of this
 *     car: currentFloor, direction, the up/down request sets. All mutations
 *     happen on that thread.
 *
 *   - External callers (the Dispatcher, the demo) communicate with the car
 *     by pushing Request objects onto a thread-safe mailbox
 *     (LinkedBlockingQueue). They NEVER touch the car's state directly.
 *
 *   - This is the actor model. It's the OPPOSITE of locking: instead of
 *     making a critical section atomic with mutex, we serialize ALL
 *     mutations by funnelling them through one thread.
 *
 * Why this design over per-elevator ReentrantLock?
 * ────────────────────────────────────────────────
 *   - The elevator is ALWAYS busy: even when no one is calling, it's
 *     moving toward its next destination on a fixed schedule (tick).
 *     If you used a lock, the drive-loop thread would have to lock/unlock
 *     on every tick, contending with hall-call appenders. With an actor,
 *     the drive-loop runs uncontended; appenders are O(1) lock-free into
 *     the queue.
 *
 *   - Reasoning: when EVERY operation needs the same state, locks degrade
 *     to "single thread held the lock continuously" -- you've reinvented a
 *     single-threaded design with extra steps. The actor cuts out the
 *     middleman.
 *
 * SCAN scheduling
 * ───────────────
 *   - When the car is moving UP, it serves every UP request at or above the
 *     current floor, then reverses to DOWN.
 *   - Symmetric for DOWN.
 *   - The scheduling data structure is two NavigableSet<Integer>:
 *       upStops    = floors to visit on the upward leg
 *       downStops  = floors to visit on the downward leg
 *   - Picking the next floor is O(log n) (`upStops.ceiling(currentFloor)`).
 *
 *   - Why two sets, not one priority queue? Because the next destination
 *     depends on direction:
 *       UP:    smallest floor strictly > current that's in upStops
 *       DOWN:  largest floor strictly < current that's in downStops
 *     Two ordered sets give both queries in O(log n) without re-sorting.
 *
 * Starvation
 * ──────────
 *   - With pure SCAN, a poor floor-1 user during a tall building's
 *     rush-up hour could wait forever. Real systems mitigate via:
 *       - LOOK (reverse at the LAST request, not the topmost floor)
 *       - C-SCAN (always serve in one direction, then jump back)
 *       - Aging (boost a request's priority after waiting > T seconds)
 *   - We implement LOOK because it's the textbook elevator algorithm and
 *     the demo can show the savings.
 */
public class Elevator {

    private final String id;
    private final int minFloor;
    private final int maxFloor;

    /*
     * Mailbox of incoming requests. Lock-free for the producer (Dispatcher
     * thread on hall-call, or rider's car-call); blocking poll on the
     * consumer side (drive loop).
     */
    private final LinkedBlockingQueue<Request> mailbox = new LinkedBlockingQueue<>();

    /*
     * Ordered stop sets. Mutated ONLY on the drive-loop thread, so a plain
     * NavigableSet would work; we use ConcurrentSkipListSet defensively so
     * that diagnostic reads from other threads are safe.
     */
    private final NavigableSet<Integer> upStops = new ConcurrentSkipListSet<>();
    private final NavigableSet<Integer> downStops = new ConcurrentSkipListSet<>();

    /*
     * Position state. AtomicInteger / volatile so external readers (the
     * Dispatcher's "which car is closest?") see consistent values without
     * needing to thread-hop into the actor.
     */
    private final AtomicInteger currentFloor = new AtomicInteger(0);
    private volatile Direction direction = Direction.IDLE;
    private volatile boolean running = false;

    private final ScheduledExecutorService driver;

    public Elevator(String id, int minFloor, int maxFloor, int startFloor) {
        if (startFloor < minFloor || startFloor > maxFloor) {
            throw new IllegalArgumentException("startFloor out of range");
        }
        this.id = id;
        this.minFloor = minFloor;
        this.maxFloor = maxFloor;
        this.currentFloor.set(startFloor);
        this.driver = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "elevator-" + id + "-drive");
            t.setDaemon(true);
            return t;
        });
    }

    public void start(long tickMillis) {
        if (running) {
            return;
        }
        running = true;
        // The drive loop ticks every `tickMillis`. Each tick:
        //   1. Drain mailbox into upStops/downStops.
        //   2. Maybe arrive at the current floor (if it's a stop).
        //   3. Advance one floor in `direction`.
        // All state mutations live on the single driver thread -> no locks.
        driver.scheduleAtFixedRate(this::tick, 0, tickMillis, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running = false;
        driver.shutdownNow();
    }

    public String getId() {
        return id;
    }

    public int getCurrentFloor() {
        return currentFloor.get();
    }

    public Direction getDirection() {
        return direction;
    }

    /*
     * Total pending work = mailbox (not yet drained) + scheduled stops.
     * Including the mailbox is necessary so an observer that calls
     * `pendingStops()` *between* a `submit()` and the next tick still sees
     * the work as pending.
     */
    public int pendingStops() {
        return upStops.size() + downStops.size() + mailbox.size();
    }

    /*
     * Public submission point. Called from any thread.
     */
    public void submit(Request request) {
        mailbox.offer(request);
    }

    /*
     * Estimate cost for this car to serve a hall call at `requestedFloor` in
     * direction `requestedDirection`. Lower = better. Used by the Dispatcher.
     *
     * Crude but classic heuristic:
     *   - If car is IDLE: cost = |distance|.
     *   - If car is moving in the same direction as the request AND the
     *     request is "ahead of" the car: cost = |distance| (it's on the
     *     way; no detour).
     *   - Otherwise: cost = |distance| + penalty for direction change.
     *
     * Reading currentFloor/direction is racy with the drive loop -- they
     * can change one tick later. That's fine: the assignment is an
     * estimate, not a contract. The car will serve whoever it's assigned.
     */
    public int costForHallCall(int requestedFloor, Direction requestedDirection) {
        int cur = currentFloor.get();
        Direction dir = direction;
        int dist = Math.abs(requestedFloor - cur);
        int rangePenalty = (maxFloor - minFloor) / 2;

        if (dir == Direction.IDLE) {
            return dist;
        }
        boolean onTheWay =
                (dir == Direction.UP && requestedFloor >= cur && requestedDirection == Direction.UP)
                || (dir == Direction.DOWN && requestedFloor <= cur && requestedDirection == Direction.DOWN);
        return onTheWay ? dist : dist + rangePenalty;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Drive loop (single-threaded, owns the state)
    // ────────────────────────────────────────────────────────────────────────

    private void tick() {
        if (!running) {
            return;
        }
        try {
            tickBody();
        } catch (Throwable t) {
            System.err.println("[" + id + "] tick threw: " + t);
            t.printStackTrace();
        }
    }

    private void tickBody() {
        drainMailbox();

        // Did we arrive at a stop on this floor?
        int floor = currentFloor.get();
        if (direction == Direction.UP && upStops.remove(floor)) {
            log("STOP at floor " + floor + " (UP)");
        } else if (direction == Direction.DOWN && downStops.remove(floor)) {
            log("STOP at floor " + floor + " (DOWN)");
        } else if (direction == Direction.IDLE) {
            // pick whichever side has work
            if (!upStops.isEmpty() || !downStops.isEmpty()) {
                pickDirection();
            }
        }

        // Decide next move.
        Integer next = nextDestination();
        if (next == null) {
            direction = Direction.IDLE;
            return;
        }
        if (next > floor) {
            direction = Direction.UP;
            currentFloor.set(floor + 1);
        } else if (next < floor) {
            direction = Direction.DOWN;
            currentFloor.set(floor - 1);
        }
    }

    private void drainMailbox() {
        Request r;
        while ((r = mailbox.poll()) != null) {
            addStop(r);
        }
    }

    private void addStop(Request r) {
        int floor = r.getFloor();
        if (floor < minFloor || floor > maxFloor) {
            return;                                 // ignore invalid
        }
        if (r.getKind() == Request.Kind.HALL_CALL) {
            if (r.getDirection() == Direction.UP) {
                upStops.add(floor);
            } else if (r.getDirection() == Direction.DOWN) {
                downStops.add(floor);
            }
        } else {
            // CAR_CALL: choose the bucket based on current direction relative
            // to the destination. If we're going UP and floor > current, it's
            // an UP stop; etc. Idle: bucket by sign relative to current.
            int cur = currentFloor.get();
            if (floor > cur) {
                upStops.add(floor);
            } else if (floor < cur) {
                downStops.add(floor);
            }
        }
        if (direction == Direction.IDLE) {
            pickDirection();
        }
    }

    /*
     * Decide the NEXT floor to advance toward.
     *
     * LOOK semantics:
     *   - If direction = UP, the next destination is the smallest upStop > cur.
     *     If none, reverse to DOWN and use largest downStop < cur.
     *   - Symmetric for DOWN.
     *   - If both empty -> null (go IDLE).
     */
    private Integer nextDestination() {
        int cur = currentFloor.get();
        if (direction == Direction.UP) {
            Integer above = upStops.ceiling(cur);   // smallest >= cur
            if (above != null) {
                return above;
            }
            // No more up requests; reverse if there's anything to do downward.
            Integer below = downStops.floor(cur);
            if (below != null) {
                direction = Direction.DOWN;
                return below;
            }
            return null;
        }
        if (direction == Direction.DOWN) {
            Integer below = downStops.floor(cur);
            if (below != null) {
                return below;
            }
            Integer above = upStops.ceiling(cur);
            if (above != null) {
                direction = Direction.UP;
                return above;
            }
            return null;
        }
        return null;
    }

    private void pickDirection() {
        int cur = currentFloor.get();
        Integer nearestUp = upStops.ceiling(cur);
        Integer nearestDown = downStops.floor(cur);
        if (nearestUp == null && nearestDown == null) {
            // Either side may have stops we PASSED -- treat them as opposite-leg.
            if (!upStops.isEmpty()) {
                direction = Direction.DOWN;        // descend toward the lowest up-pickup
            } else if (!downStops.isEmpty()) {
                direction = Direction.UP;          // ascend toward the highest down-pickup
            }
            return;
        }
        if (nearestUp == null) {
            direction = Direction.DOWN;
        } else if (nearestDown == null) {
            direction = Direction.UP;
        } else {
            direction = (nearestUp - cur) <= (cur - nearestDown) ? Direction.UP : Direction.DOWN;
        }
    }

    private void log(String msg) {
        System.out.println("  [" + id + "] " + msg);
    }
}
