package LLD.LldQuestions.HotelBooking;

import LLD.LldQuestions.HotelBooking.exceptions.RoomUnavailableException;
import LLD.LldQuestions.HotelBooking.models.DateRange;
import LLD.LldQuestions.HotelBooking.models.Reservation;
import LLD.LldQuestions.HotelBooking.models.Room;
import LLD.LldQuestions.HotelBooking.models.RoomType;
import LLD.LldQuestions.HotelBooking.repository.ReservationRepository;
import LLD.LldQuestions.HotelBooking.repository.RoomRepository;
import LLD.LldQuestions.HotelBooking.service.HotelBookingService;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * Demo entry point.
 *
 *   Scenario 1: Thundering herd on overlapping range. 50 users race to
 *               reserve Room 101 for Dec 12-14. Exactly ONE must win.
 *
 *   Scenario 2: Adjacent-but-not-overlapping. Two users book the SAME room
 *               for [Dec 12, Dec 14) and [Dec 14, Dec 16). Both must succeed
 *               because half-open ranges respect the checkout-day rule.
 *
 *   Scenario 3: Idempotency. The same request id, sent twice, must produce
 *               ONE reservation, not two.
 *
 *   Scenario 4: Cancel + rebook. After we cancel a reservation, an
 *               overlapping range must become bookable again.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        RoomRepository roomRepository = new RoomRepository();
        ReservationRepository reservationRepository = new ReservationRepository();

        roomRepository.add(new Room("R101", RoomType.STANDARD, 2));
        roomRepository.add(new Room("R102", RoomType.STANDARD, 2));
        roomRepository.add(new Room("R201", RoomType.DELUXE, 3));
        roomRepository.add(new Room("R301", RoomType.SUITE, 4));

        HotelBookingService service =
                new HotelBookingService(roomRepository, reservationRepository);

        runThunderingHerd(service);
        runAdjacentRanges(service);
        runIdempotency(service);
        runCancelAndRebook(service);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenarios
    // ────────────────────────────────────────────────────────────────────────

    private static void runThunderingHerd(HotelBookingService service) throws Exception {
        System.out.println("\n=== Scenario 1: 50 users race for Room 101, Dec 12-14 ===");

        DateRange contested = new DateRange(LocalDate.of(2026, 12, 12),
                LocalDate.of(2026, 12, 14));
        int contestants = 50;

        AtomicInteger winners = new AtomicInteger();
        AtomicInteger losers = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(contestants);
        ExecutorService pool = Executors.newFixedThreadPool(contestants);

        for (int i = 0; i < contestants; i++) {
            final String userId = "u" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    Reservation r = service.reserve(
                            /*idempotency=*/ UUID.randomUUID().toString(),
                            userId, "R101", contested);
                    winners.incrementAndGet();
                    System.out.println("  WINNER " + userId + " -> " + r.getId());
                } catch (RoomUnavailableException e) {
                    losers.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("  unexpected: " + e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        System.out.println("  winners=" + winners.get() + " losers=" + losers.get());
        if (winners.get() != 1) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: expected exactly 1 winner, got " + winners.get());
        }
    }

    private static void runAdjacentRanges(HotelBookingService service) {
        System.out.println("\n=== Scenario 2: adjacent ranges on the same room both succeed ===");

        DateRange a = new DateRange(LocalDate.of(2027, 1, 5), LocalDate.of(2027, 1, 7));
        DateRange b = new DateRange(LocalDate.of(2027, 1, 7), LocalDate.of(2027, 1, 9));

        Reservation ra = service.reserve(UUID.randomUUID().toString(), "alice", "R102", a);
        Reservation rb = service.reserve(UUID.randomUUID().toString(), "bob",   "R102", b);

        System.out.println("  alice booked " + ra.getDates());
        System.out.println("  bob   booked " + rb.getDates() + "  (same room, no overlap)");
    }

    private static void runIdempotency(HotelBookingService service) {
        System.out.println("\n=== Scenario 3: same request id twice -> ONE reservation ===");

        String idempotencyKey = "retry-test-" + UUID.randomUUID();
        DateRange r = new DateRange(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 3));

        Reservation first = service.reserve(idempotencyKey, "carol", "R201", r);
        Reservation again = service.reserve(idempotencyKey, "carol", "R201", r);

        System.out.println("  first  id=" + first.getId());
        System.out.println("  retry  id=" + again.getId() + "  (must equal first)");

        if (!first.getId().equals(again.getId())) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: idempotency did not dedup; got " + first.getId()
                            + " vs " + again.getId());
        }
    }

    private static void runCancelAndRebook(HotelBookingService service) {
        System.out.println("\n=== Scenario 4: cancel -> the range becomes bookable again ===");

        DateRange r = new DateRange(LocalDate.of(2027, 3, 10), LocalDate.of(2027, 3, 14));
        Reservation res = service.reserve(UUID.randomUUID().toString(), "dave", "R301", r);
        System.out.println("  dave reserved " + r + " on R301");

        try {
            service.reserve(UUID.randomUUID().toString(), "erin", "R301", r);
            throw new IllegalStateException("erin should have been rejected!");
        } catch (RoomUnavailableException e) {
            System.out.println("  erin rejected (as expected): " + e.getMessage());
        }

        service.cancel(res.getId(), "dave");
        System.out.println("  dave cancelled");

        Reservation erinRes = service.reserve(UUID.randomUUID().toString(), "erin", "R301", r);
        System.out.println("  erin now reserved " + erinRes.getDates() + " on R301");
    }
}
