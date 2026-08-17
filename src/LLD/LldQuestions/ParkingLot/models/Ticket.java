package LLD.LldQuestions.ParkingLot.models;

import java.time.Instant;

/*
 * Immutable receipt handed to a driver on park-in. Acts as a capability:
 *  - you must present the ticket id to exit and to pay,
 *  - the lot enforces ownership server-side (we don't trust the ticket alone).
 *
 * Why immutable? Because tickets get cached, logged, printed, scanned -- if
 * any of those callers could mutate the object, "the ticket Alice scanned at
 * 09:00" could differ from "the ticket the gate looked up at 18:00" even
 * though they're the same logical object. Immutability removes that class of
 * bug for free.
 */
public final class Ticket {

    private final String id;            // UUID-style, globally unique
    private final String plate;
    private final String spotId;
    private final Instant issuedAt;

    public Ticket(String id, String plate, String spotId, Instant issuedAt) {
        this.id = id;
        this.plate = plate;
        this.spotId = spotId;
        this.issuedAt = issuedAt;
    }

    public String getId() {
        return id;
    }

    public String getPlate() {
        return plate;
    }

    public String getSpotId() {
        return spotId;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    @Override
    public String toString() {
        return "Ticket{" + id + " plate=" + plate + " spot=" + spotId + "}";
    }
}
