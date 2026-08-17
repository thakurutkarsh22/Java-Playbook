package LLD.LldQuestions.HotelBooking.models;

/*
 * A physical hotel room. State (which intervals are reserved on which dates)
 * lives on the Reservation objects, NOT here -- a Room is a long-lived,
 * essentially immutable entity in the LLD scope. Pricing changes per-night
 * are policy that lives in a separate strategy.
 */
public class Room {

    private final String id;
    private final RoomType type;
    private final int capacity;             // max guests

    public Room(String id, RoomType type, int capacity) {
        this.id = id;
        this.type = type;
        this.capacity = capacity;
    }

    public String getId() {
        return id;
    }

    public RoomType getType() {
        return type;
    }

    public int getCapacity() {
        return capacity;
    }

    @Override
    public String toString() {
        return id + "(" + type + ")";
    }
}
