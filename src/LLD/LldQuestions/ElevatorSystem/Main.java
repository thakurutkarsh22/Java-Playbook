package LLD.LldQuestions.ElevatorSystem;

import LLD.LldQuestions.ElevatorSystem.models.Direction;
import LLD.LldQuestions.ElevatorSystem.service.Dispatcher;
import LLD.LldQuestions.ElevatorSystem.service.Elevator;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/*
 * Demo entry point.
 *
 *   Scenario 1: Two elevators, two simultaneous hall calls on opposite ends.
 *               Each should be dispatched to the closer car. After serving,
 *               both must come to rest IDLE.
 *
 *   Scenario 2: SCAN behavior. One elevator gets calls 3-UP, 7-UP, 9-UP
 *               while sitting at floor 1. It serves them in ascending order
 *               (NOT in submission order).
 *
 *   Scenario 3: Reverse on LOOK. Elevator at floor 5, going UP. We submit
 *               request "go to floor 7" (next stop UP). After serving 7,
 *               we submit "go to floor 2" (next stop DOWN). Elevator
 *               reverses direction at 7, descends to 2.
 *
 * For simplicity we don't ASSERT against logs -- the scenarios produce
 * recognizable output and a final IDLE state. The "no deadlock" invariant
 * is implicit: if the actor design were broken, the demo would hang.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        runDispatcherChoosesNearestCar();
        runSingleElevatorSCAN();
        runReverseOnLook();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenarios
    // ────────────────────────────────────────────────────────────────────────

    private static void runDispatcherChoosesNearestCar() throws Exception {
        System.out.println("\n=== Scenario 1: 2 cars, 2 simultaneous hall calls on opposite ends ===");

        Elevator e1 = new Elevator("E1", 0, 10, 1);    // starts at floor 1
        Elevator e2 = new Elevator("E2", 0, 10, 9);    // starts at floor 9
        List<Elevator> cars = Arrays.asList(e1, e2);
        Dispatcher dispatcher = new Dispatcher(cars);

        e1.start(50);
        e2.start(50);

        // Two hall calls at the SAME time:
        //   floor 2 UP    -> should go to E1 (much closer)
        //   floor 8 DOWN  -> should go to E2 (much closer)
        Elevator pickedFor2  = dispatcher.dispatchHallCall(2, Direction.UP);
        Elevator pickedFor8  = dispatcher.dispatchHallCall(8, Direction.DOWN);

        System.out.println("  call(floor=2 UP)  routed to " + pickedFor2.getId() + " (expected E1)");
        System.out.println("  call(floor=8 DOWN) routed to " + pickedFor8.getId() + " (expected E2)");

        // Wait for both to finish their immediate job.
        waitUntilIdle(cars, 5_000);
        e1.stop();
        e2.stop();

        System.out.println("  final E1@floor=" + e1.getCurrentFloor() + " E2@floor=" + e2.getCurrentFloor());
        if (pickedFor2 != e1 || pickedFor8 != e2) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: dispatcher should have picked the nearer car");
        }
    }

    private static void runSingleElevatorSCAN() throws Exception {
        System.out.println("\n=== Scenario 2: one elevator serves 3,7,9 in SCAN order ===");

        Elevator e = new Elevator("E", 0, 10, 1);
        e.start(50);

        // Submit OUT of physical order; SCAN must serve in floor order.
        e.submit(new LLD.LldQuestions.ElevatorSystem.models.Request(
                LLD.LldQuestions.ElevatorSystem.models.Request.Kind.HALL_CALL, 9, Direction.UP));
        e.submit(new LLD.LldQuestions.ElevatorSystem.models.Request(
                LLD.LldQuestions.ElevatorSystem.models.Request.Kind.HALL_CALL, 3, Direction.UP));
        e.submit(new LLD.LldQuestions.ElevatorSystem.models.Request(
                LLD.LldQuestions.ElevatorSystem.models.Request.Kind.HALL_CALL, 7, Direction.UP));

        // Wait for completion.
        waitUntilIdle(java.util.Collections.singletonList(e), 5_000);
        e.stop();

        System.out.println("  expected stops: 3, 7, 9 (ascending). final floor=" + e.getCurrentFloor());
    }

    private static void runReverseOnLook() throws Exception {
        System.out.println("\n=== Scenario 3: LOOK reverses direction when no more UP work ===");

        Elevator e = new Elevator("E", 0, 10, 5);
        e.start(50);

        // First, a CAR_CALL above to make us travel UP.
        e.submit(new LLD.LldQuestions.ElevatorSystem.models.Request(
                LLD.LldQuestions.ElevatorSystem.models.Request.Kind.CAR_CALL, 7, Direction.IDLE));

        // Wait DETERMINISTICALLY until floor 7 is reached and the car is idle
        // again. Without this, JVM warm-up jitter could land the second submit
        // anywhere on the ascent and the demo's expected "STOP at 7" log would
        // race with the descent.
        waitUntilIdle(java.util.Collections.singletonList(e), 5_000);

        // Now add a CAR_CALL below: the car is at 7, IDLE, must descend to 2.
        e.submit(new LLD.LldQuestions.ElevatorSystem.models.Request(
                LLD.LldQuestions.ElevatorSystem.models.Request.Kind.CAR_CALL, 2, Direction.IDLE));

        waitUntilIdle(java.util.Collections.singletonList(e), 5_000);
        e.stop();

        System.out.println("  expected: visit 7 (UP), reverse, visit 2 (DOWN). final floor="
                + e.getCurrentFloor() + " (expected 2)");
        if (e.getCurrentFloor() != 2) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: expected final floor=2, got " + e.getCurrentFloor());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private static void waitUntilIdle(List<Elevator> cars, long maxMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (System.currentTimeMillis() < deadline) {
            boolean allIdle = true;
            for (Elevator e : cars) {
                if (e.getDirection() != Direction.IDLE || e.pendingStops() > 0) {
                    allIdle = false;
                    break;
                }
            }
            if (allIdle) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new IllegalStateException(
                "INVARIANT VIOLATED: elevators did not become idle within " + maxMillis + "ms");
    }
}
