package LLD.LldQuestions.ParkingLot;

import LLD.LldQuestions.ParkingLot.exceptions.LotFullException;
import LLD.LldQuestions.ParkingLot.models.ParkingFloor;
import LLD.LldQuestions.ParkingLot.models.ParkingSpot;
import LLD.LldQuestions.ParkingLot.models.Ticket;
import LLD.LldQuestions.ParkingLot.models.Vehicle;
import LLD.LldQuestions.ParkingLot.models.VehicleSize;
import LLD.LldQuestions.ParkingLot.repository.TicketRepository;
import LLD.LldQuestions.ParkingLot.service.ParkingLotService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * Demo entry point.
 *
 *   Scenario 1: Thundering herd. 50 cars race to park; only N=10 CAR spots
 *               exist. Exactly 10 must succeed; the other 40 must hit
 *               LotFullException -- no double-occupancy.
 *
 *   Scenario 2: Up-grade. With CAR spots full and BIKE/TRUCK spots free, a
 *               BIKE must still find a parking spot by escalating into a CAR
 *               or TRUCK slot.
 *
 *   Scenario 3: Park / unpark / re-park cycle. After all CAR spots fill up,
 *               releasing one ticket must let the *next* incoming CAR park
 *               into exactly that spot -- proves the permit/spot pair is
 *               correctly returned on release.
 */
public class Main {

    public static void main(String[] args) throws InterruptedException {
        ParkingLotService lot = buildLot(
                /*bikePerFloor=*/ 4,
                /*carPerFloor=*/  5,
                /*truckPerFloor=*/ 1,
                /*floors=*/        2);

        runThunderingHerd(lot);
        runUpgradeScenario(lot);
        runReleaseAndReparkScenario(lot);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenarios
    // ────────────────────────────────────────────────────────────────────────

    private static void runThunderingHerd(ParkingLotService lot) throws InterruptedException {
        System.out.println("\n=== Scenario 1: 50 cars race; 12 CAR-acceptable spots exist (10 CAR + 2 TRUCK) ===");

        int contestants = 50;
        // A CAR fits in any spot of size >= CAR -> CAR + TRUCK spots both qualify.
        // capacity = 2 floors × (5 CAR + 1 TRUCK) = 12.
        int capacity = 12;

        AtomicInteger parked = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ConcurrentLinkedQueue<Ticket> issued = new ConcurrentLinkedQueue<>();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(contestants);
        ExecutorService pool = Executors.newFixedThreadPool(contestants);

        for (int i = 0; i < contestants; i++) {
            final String plate = "CAR-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    Ticket t = lot.park(new Vehicle(plate, VehicleSize.CAR));
                    parked.incrementAndGet();
                    issued.add(t);
                } catch (LotFullException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("Unexpected: " + e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        System.out.println("  parked=" + parked.get() + " rejected=" + rejected.get());
        if (parked.get() != capacity || rejected.get() != contestants - capacity) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: expected " + capacity + " parked / "
                            + (contestants - capacity) + " rejected; got "
                            + parked.get() + " / " + rejected.get());
        }

        // Empty the lot for the next scenario.
        for (Ticket t : issued) {
            lot.unpark(t.getId());
        }
    }

    private static void runUpgradeScenario(ParkingLotService lot) {
        System.out.println("\n=== Scenario 2: bike up-grade when bike spots are full ===");

        // Fill every BIKE spot first (2 floors × 4 = 8).
        List<Ticket> bikeTickets = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            bikeTickets.add(lot.park(new Vehicle("BIKE-" + i, VehicleSize.BIKE)));
        }

        // 9th bike: BIKE permits exhausted. Should escalate into a CAR spot.
        Ticket upgraded = lot.park(new Vehicle("BIKE-OVERFLOW", VehicleSize.BIKE));
        System.out.println("  9th bike parked in spot=" + upgraded.getSpotId()
                + " (must be a CAR or TRUCK spot)");

        if (!upgraded.getSpotId().contains("C") && !upgraded.getSpotId().contains("T")) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: expected up-grade to CAR/TRUCK spot, got "
                            + upgraded.getSpotId());
        }

        // Clean up
        lot.unpark(upgraded.getId());
        for (Ticket t : bikeTickets) {
            lot.unpark(t.getId());
        }
    }

    private static void runReleaseAndReparkScenario(ParkingLotService lot) {
        System.out.println("\n=== Scenario 3: release returns the permit so the next park succeeds ===");

        // Fill every CAR-acceptable spot (10 CAR + 2 TRUCK = 12 total).
        List<Ticket> filled = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            filled.add(lot.park(new Vehicle("CAR-" + i, VehicleSize.CAR)));
        }

        // Now a CAR must be rejected.
        boolean rejected = false;
        try {
            lot.park(new Vehicle("CAR-AFTER-FULL", VehicleSize.CAR));
        } catch (LotFullException e) {
            rejected = true;
        }
        if (!rejected) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: lot was full but a CAR was admitted");
        }

        // Release ONE ticket; the very next CAR must succeed and take that exact spot.
        Ticket released = filled.remove(0);
        lot.unpark(released.getId());

        Ticket replacement = lot.park(new Vehicle("CAR-REPLACEMENT", VehicleSize.CAR));
        System.out.println("  released " + released.getSpotId()
                + " -> replacement parked at " + replacement.getSpotId());

        if (!released.getSpotId().equals(replacement.getSpotId())) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: replacement should have taken the just-freed spot "
                            + released.getSpotId() + " but got " + replacement.getSpotId());
        }

        // Clean up.
        lot.unpark(replacement.getId());
        for (Ticket t : filled) {
            lot.unpark(t.getId());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Wiring
    // ────────────────────────────────────────────────────────────────────────

    private static ParkingLotService buildLot(int bikePerFloor,
                                              int carPerFloor,
                                              int truckPerFloor,
                                              int floors) {
        List<ParkingFloor> floorList = new ArrayList<>();
        for (int f = 1; f <= floors; f++) {
            List<ParkingSpot> spots = new ArrayList<>();
            for (int i = 1; i <= bikePerFloor; i++) {
                spots.add(new ParkingSpot("F" + f + "-B" + i, VehicleSize.BIKE));
            }
            for (int i = 1; i <= carPerFloor; i++) {
                spots.add(new ParkingSpot("F" + f + "-C" + i, VehicleSize.CAR));
            }
            for (int i = 1; i <= truckPerFloor; i++) {
                spots.add(new ParkingSpot("F" + f + "-T" + i, VehicleSize.TRUCK));
            }
            floorList.add(new ParkingFloor("F" + f, spots));
        }
        return new ParkingLotService(floorList, new TicketRepository());
    }

    @SuppressWarnings("unused")
    private static <T> List<T> list(T... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    @SuppressWarnings("unused")
    private static void awaitTermination(ExecutorService pool) throws InterruptedException {
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }
}
