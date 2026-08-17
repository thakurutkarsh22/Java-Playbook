package LLD.LldQuestions.HotelBooking.repository;

import LLD.LldQuestions.HotelBooking.models.Reservation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║                      ReservationRepository                             ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * Storage: per-room list of reservations.
 *   roomReservations : roomId -> List<Reservation>
 *
 * Concurrency model
 * ─────────────────
 *   - ConcurrentHashMap keyed by roomId. Atomic computeIfAbsent for new-room
 *     creation -- racing callers see the same list.
 *   - The per-room List is mutated ONLY under the per-room ReentrantLock
 *     held by HotelBookingService. So the list itself does not need to be
 *     thread-safe; we keep it a plain ArrayList.
 *
 *   - Reads (`getActiveForRoom`) snapshot under the same lock or accept
 *     "weak-consistency" reads when used for diagnostics. The hot conflict
 *     check ALWAYS runs under the lock.
 *
 * Why per-room list and not a global list?
 *   - The conflict scan is O(reservations-on-this-room). 99% of rooms have
 *     < 1000 lifetime reservations. A global list would be O(N × rooms).
 *   - In real DB land this is "WHERE room_id = ? AND tstzrange(check_in,
 *     check_out, '[)') && tstzrange(?, ?, '[)')" -- a btree+gist index lookup.
 */
public class ReservationRepository {

    private final Map<String, List<Reservation>> byRoom = new ConcurrentHashMap<>();
    private final Map<String, Reservation> byId = new ConcurrentHashMap<>();

    public void save(Reservation reservation) {
        byId.put(reservation.getId(), reservation);
        byRoom.computeIfAbsent(reservation.getRoomId(), k -> new ArrayList<>())
              .add(reservation);
    }

    public Reservation get(String id) {
        return byId.get(id);
    }

    /*
     * Returns an unmodifiable view of all reservations for a room. Caller
     * must hold the per-room lock if reading for conflict-detection.
     */
    public List<Reservation> getAllForRoom(String roomId) {
        List<Reservation> raw = byRoom.get(roomId);
        if (raw == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(raw);
    }
}
