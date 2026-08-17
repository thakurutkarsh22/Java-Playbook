package LLD.LldQuestions.ParkingLot.service;

import LLD.LldQuestions.ParkingLot.exceptions.InvalidTicketException;
import LLD.LldQuestions.ParkingLot.exceptions.LotFullException;
import LLD.LldQuestions.ParkingLot.models.ParkingFloor;
import LLD.LldQuestions.ParkingLot.models.ParkingSpot;
import LLD.LldQuestions.ParkingLot.models.Ticket;
import LLD.LldQuestions.ParkingLot.models.Vehicle;
import LLD.LldQuestions.ParkingLot.repository.TicketRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/*
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║                         ParkingLotService                              ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * The lot is a SHARDED data structure: each ParkingFloor owns its own locks
 * and its own per-size semaphores. The service is therefore lock-FREE itself
 * -- it does no synchronization, it just routes a request to the right floor.
 *
 * Why no service-level lock?
 *   - There is no compound action at the service level that spans floors.
 *     `park()` picks one floor and delegates; `unpark()` is keyed by ticket
 *     which already names the floor. So there's nothing to protect.
 *   - Adding a service-level lock would *serialize* the whole lot, which is
 *     the opposite of what we want.
 *
 * Floor-selection policy
 * ──────────────────────
 * The naive policy: try floors in order, take the first that admits us.
 * Pros: trivial, no state. Cons: floor 1 always fills first; floors 4/5
 * stay empty until the lot is nearly full ("hot floor" wear pattern).
 *
 * Production policy: track a `nextFloorHint` round-robin index, OR keep an
 * O(1) priority-queue of floors keyed by free count. Out of scope for this
 * basic LLD; the entry point is `ParkingLotService.setFloorSelectionPolicy`
 * if you want to inject a Strategy later.
 */
public class ParkingLotService {

    private final List<ParkingFloor> floors;
    private final TicketRepository ticketRepository;

    public ParkingLotService(List<ParkingFloor> floors, TicketRepository ticketRepository) {
        this.floors = floors;
        this.ticketRepository = ticketRepository;
    }

    public Ticket park(Vehicle vehicle) {
        for (ParkingFloor floor : floors) {
            ParkingSpot spot = floor.reserveAndClaim(vehicle);
            if (spot == null) {
                continue;                       // this floor is full for this size, try next
            }
            Ticket ticket = new Ticket(
                    UUID.randomUUID().toString(),
                    vehicle.getPlate(),
                    spot.getId(),
                    Instant.now());
            ticketRepository.save(ticket);
            return ticket;
        }
        throw new LotFullException(
                "No spot available for vehicle " + vehicle + " across "
                        + floors.size() + " floor(s)");
    }

    public void unpark(String ticketId) {
        Ticket ticket = ticketRepository.get(ticketId);
        if (ticket == null) {
            throw new InvalidTicketException("Unknown ticket: " + ticketId);
        }

        ParkingSpot spot = findSpot(ticket.getSpotId());
        if (spot == null) {
            throw new InvalidTicketException(
                    "Ticket refers to unknown spot: " + ticket.getSpotId());
        }
        // Plate validation guards against ticket swap at the gate. If a
        // staff member or attacker hands the gate someone else's ticket, the
        // plate read by the gate camera won't match `ticket.getPlate()`.
        if (!ticket.getPlate().equals(spot.getOccupiedBy())) {
            throw new InvalidTicketException(
                    "Ticket plate does not match parked vehicle for spot " + spot.getId());
        }

        // The floor.release() call also returns the per-size permit -- the
        // semaphore count is what allows the next park() to succeed.
        findFloorOf(spot).release(spot, ticket.getPlate());
        ticketRepository.delete(ticketId);
    }

    public int availablePermits(int floorIndex, LLD.LldQuestions.ParkingLot.models.VehicleSize size) {
        return floors.get(floorIndex).availablePermits(size);
    }

    private ParkingSpot findSpot(String spotId) {
        for (ParkingFloor floor : floors) {
            for (ParkingSpot spot : floor.getSpots()) {
                if (spot.getId().equals(spotId)) {
                    return spot;
                }
            }
        }
        return null;
    }

    private ParkingFloor findFloorOf(ParkingSpot spot) {
        for (ParkingFloor floor : floors) {
            if (floor.getSpots().contains(spot)) {
                return floor;
            }
        }
        // Truly unreachable: we resolved `spot` from `floors` two lines above.
        throw new IllegalStateException("Spot not on any floor: " + spot.getId());
    }
}
