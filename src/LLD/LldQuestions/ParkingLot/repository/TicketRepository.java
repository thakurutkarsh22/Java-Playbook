package LLD.LldQuestions.ParkingLot.repository;

import LLD.LldQuestions.ParkingLot.models.Ticket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * In-memory ticket store. The single read path (`get` on exit) and the single
 * write path (`save` on entry) are both naturally one-to-one with a real DB
 * row, so a ConcurrentHashMap is the right shape: O(1) get/put, atomic
 * `putIfAbsent` if we ever wanted exactly-once ticket creation across retries.
 *
 * Why not a plain HashMap synchronized externally? Because reads vastly
 * outnumber writes on a parking-lot exit path (status displays, gate scans,
 * receipt printing), and CHM's lock-striping gives non-blocking gets.
 */
public class TicketRepository {

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public void save(Ticket ticket) {
        tickets.put(ticket.getId(), ticket);
    }

    public Ticket get(String ticketId) {
        return tickets.get(ticketId);
    }

    public void delete(String ticketId) {
        tickets.remove(ticketId);
    }
}
