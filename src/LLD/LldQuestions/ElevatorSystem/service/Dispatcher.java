package LLD.LldQuestions.ElevatorSystem.service;

import LLD.LldQuestions.ElevatorSystem.models.Direction;
import LLD.LldQuestions.ElevatorSystem.models.Request;

import java.util.List;

/*
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║                            Dispatcher                                  ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * The Dispatcher routes external HALL CALL requests to the BEST elevator.
 * "Best" is defined by Elevator.costForHallCall, currently a SCAN-aware
 * heuristic (penalize direction-changes; cheap when on the way).
 *
 * Concurrency model
 * ─────────────────
 *   - Dispatcher is STATELESS. It owns the list of elevators (read-only
 *     after construction) and routes by calling each elevator's
 *     costForHallCall (lock-free read) then submitting to the winner
 *     (lock-free mailbox push).
 *
 *   - Therefore the Dispatcher has NO LOCKS. Concurrent dispatches just
 *     each read each car's current floor/direction and pick the best.
 *     Two simultaneous dispatches may pick the SAME car (the better-by-cost
 *     one), which is fine: the car will serve them in arrival order via
 *     its mailbox.
 *
 *   - The "best" choice may be slightly stale -- by the time the car reads
 *     the mailbox, it has advanced a tick. That's acceptable for elevator
 *     dispatching; the cost estimate doesn't change wildly in 100 ms.
 *
 * Why no PriorityBlockingQueue here?
 *   - You'd want one if the system kept its OWN queue of un-assigned hall
 *     calls and reassigned them as cars freed up. Real systems do this.
 *     For our LLD, immediate dispatch on submission is enough to teach
 *     the multi-actor coordination idea.
 */
public class Dispatcher {

    private final List<Elevator> elevators;

    public Dispatcher(List<Elevator> elevators) {
        if (elevators == null || elevators.isEmpty()) {
            throw new IllegalArgumentException("at least one elevator required");
        }
        this.elevators = elevators;
    }

    /*
     * Route a hall call to the cheapest-cost elevator.
     */
    public Elevator dispatchHallCall(int floor, Direction direction) {
        Elevator best = null;
        int bestCost = Integer.MAX_VALUE;
        for (Elevator e : elevators) {
            int cost = e.costForHallCall(floor, direction);
            if (cost < bestCost) {
                bestCost = cost;
                best = e;
            }
        }
        if (best == null) {
            best = elevators.get(0);
        }
        best.submit(new Request(Request.Kind.HALL_CALL, floor, direction));
        return best;
    }

    /*
     * A car call (the rider, already inside, pressing their destination
     * button). The caller already knows which elevator they are in, so the
     * dispatcher just forwards.
     */
    public void submitCarCall(Elevator car, int destinationFloor) {
        car.submit(new Request(Request.Kind.CAR_CALL, destinationFloor, Direction.IDLE));
    }
}
