package LLD.LldQuestions.HotelBooking.service;

import LLD.LldQuestions.HotelBooking.exceptions.RoomUnavailableException;
import LLD.LldQuestions.HotelBooking.models.DateRange;
import LLD.LldQuestions.HotelBooking.models.Reservation;
import LLD.LldQuestions.HotelBooking.models.Room;
import LLD.LldQuestions.HotelBooking.models.RoomType;
import LLD.LldQuestions.HotelBooking.repository.ReservationRepository;
import LLD.LldQuestions.HotelBooking.repository.RoomRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/*
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║                       HotelBookingService                              ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * The only thing structurally different from TicketBooking:
 *
 *   TicketBooking conflict predicate:   "are any of these SEATs already taken?"
 *                                        -> set-membership test (O(1) per seat)
 *
 *   HotelBooking conflict predicate:    "does this DATE RANGE overlap any existing
 *                                        reservation on this room?"
 *                                        -> interval-overlap scan (O(n))
 *
 * Everything else -- per-key fair ReentrantLock, ConcurrentHashMap-of-locks,
 * lock-then-mutate -- is identical to TicketBooking. The lesson is: ONCE
 * you have the "lock a key, do a compound action" skeleton, harder predicates
 * slot in without changing the concurrency design.
 *
 * Lock granularity: per-room. Why?
 *
 *   - Different rooms are FULLY INDEPENDENT. Reserving Room 401 cannot
 *     possibly conflict with Room 402. Per-room locks let those two threads
 *     run in parallel.
 *   - A naive global lock would serialize the entire hotel, including
 *     read-only "search availability" queries.
 *   - Finer (per-room-per-month) is possible but complicates cancel logic
 *     and the gains evaporate -- conflicts are already isolated to a room.
 *
 * Why FAIR locks here?
 *
 *   - Hotel users perceive "I clicked Reserve before you did" as a
 *     fairness contract. The cost (~10-15%) is paid happily because we
 *     are nowhere near the lock-contention bottleneck for a hotel.
 *
 * Two-phase booking? (Compare to TicketBooking.)
 *
 *   - TicketBooking has a slow EXTERNAL payment step between "hold" and
 *     "confirm". We modeled that with a TTL'd soft hold.
 *   - In this basic LLD we treat the reservation as atomic -- check + write
 *     in one lock acquisition. To extend, swap in the same SeatLockProvider
 *     pattern from TicketBooking, keyed by (roomId, dateRange) -- the lock
 *     storage would be `Map<roomId, List<HeldRange>>` and the conflict test
 *     would walk both committed reservations AND active holds.
 *
 * Idempotency on retry?
 *
 *   - We pass a per-request `clientRequestId`. If we've already created a
 *     reservation for it, we return the EXISTING one instead of double-booking.
 *   - This is the same idempotency pattern as AtmTransfer (project #6).
 */
public class HotelBookingService {

    private final RoomRepository roomRepository;
    private final ReservationRepository reservationRepository;

    /*
     * Per-room ReentrantLock. computeIfAbsent gives racing callers the SAME
     * lock instance for a brand-new room. Fair to preserve "first-clicker
     * wins" for hot dates.
     */
    private final Map<String, ReentrantLock> roomLocks = new ConcurrentHashMap<>();

    /*
     * Idempotency table. Maps clientRequestId -> reservationId.
     * A retried request returns the original reservation rather than booking
     * twice. In production this would be a DB unique index on
     * (client_request_id) with the conflict resolved via SELECT-after-INSERT.
     */
    private final Map<String, String> idempotency = new ConcurrentHashMap<>();

    public HotelBookingService(RoomRepository roomRepository,
                               ReservationRepository reservationRepository) {
        this.roomRepository = roomRepository;
        this.reservationRepository = reservationRepository;
    }

    /*
     * Book a specific room for a specific date range.
     *
     *   clientRequestId : caller-supplied; same id on retry -> same reservation.
     *                     Pass null to disable dedup (NOT recommended).
     */
    public Reservation reserve(String clientRequestId,
                               String userId,
                               String roomId,
                               DateRange dates) {
        if (clientRequestId != null) {
            String existing = idempotency.get(clientRequestId);
            if (existing != null) {
                return reservationRepository.get(existing);
            }
        }

        Room room = roomRepository.get(roomId);
        if (room == null) {
            throw new IllegalArgumentException("Unknown room: " + roomId);
        }

        ReentrantLock roomLock = lockFor(roomId);
        roomLock.lock();
        try {
            // ── DOUBLE-CHECK after acquiring the lock ────────────────────
            // Another thread with the SAME idempotency key might have raced
            // us to the lock and already created the reservation. Re-check
            // inside the critical section to make the read-modify-write
            // genuinely atomic.
            if (clientRequestId != null) {
                String existing = idempotency.get(clientRequestId);
                if (existing != null) {
                    return reservationRepository.get(existing);
                }
            }

            // ── CONFLICT CHECK: walk existing reservations on this room ──
            // Skip cancelled ones (they don't reserve anything). Stop on
            // first overlap to avoid scanning the whole history.
            List<Reservation> existing = reservationRepository.getAllForRoom(roomId);
            for (Reservation r : existing) {
                if (!r.isActive()) {
                    continue;
                }
                if (r.getDates().overlaps(dates)) {
                    throw new RoomUnavailableException(
                            "Room " + roomId + " unavailable for " + dates
                                    + "; conflicts with reservation " + r.getId()
                                    + " " + r.getDates());
                }
            }

            // ── COMMIT ────────────────────────────────────────────────────
            Reservation res = new Reservation(
                    UUID.randomUUID().toString(), userId, roomId, dates);
            reservationRepository.save(res);
            if (clientRequestId != null) {
                idempotency.put(clientRequestId, res.getId());
            }
            return res;
        } finally {
            roomLock.unlock();
        }
    }

    public void cancel(String reservationId, String userId) {
        Reservation r = reservationRepository.get(reservationId);
        if (r == null || !r.getUserId().equals(userId)) {
            throw new IllegalArgumentException(
                    "Unknown reservation or owner mismatch: " + reservationId);
        }
        ReentrantLock roomLock = lockFor(r.getRoomId());
        roomLock.lock();
        try {
            r.cancel();
        } finally {
            roomLock.unlock();
        }
    }

    /*
     * Search for ANY available room of a given type over a given range.
     * Linear walk over rooms; under each room's lock we do the conflict
     * check. Returns null if none available.
     *
     * For a real hotel the n is small (~100 rooms). For a chain (~10k rooms)
     * we'd switch to a precomputed availability index keyed by date.
     */
    public Room findAvailable(RoomType type, DateRange dates) {
        for (Room room : roomRepository.all()) {
            if (room.getType() != type) {
                continue;
            }
            ReentrantLock roomLock = lockFor(room.getId());
            roomLock.lock();
            try {
                boolean conflict = false;
                for (Reservation r : reservationRepository.getAllForRoom(room.getId())) {
                    if (r.isActive() && r.getDates().overlaps(dates)) {
                        conflict = true;
                        break;
                    }
                }
                if (!conflict) {
                    return room;
                }
            } finally {
                roomLock.unlock();
            }
        }
        return null;
    }

    private ReentrantLock lockFor(String roomId) {
        return roomLocks.computeIfAbsent(roomId, k -> new ReentrantLock(true));
    }
}
