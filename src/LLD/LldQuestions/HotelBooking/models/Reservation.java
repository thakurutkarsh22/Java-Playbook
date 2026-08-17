package LLD.LldQuestions.HotelBooking.models;

/*
 * A confirmed (or cancelled) reservation for one room over one date range.
 *
 * status is `volatile` because cancel() flips it from CONFIRMED -> CANCELLED
 * without acquiring the per-room lock (cancel routes through the service
 * which DOES hold the lock; this is defense-in-depth for diagnostics reads).
 */
public class Reservation {

    private final String id;
    private final String userId;
    private final String roomId;
    private final DateRange dates;
    private volatile ReservationStatus status;

    public Reservation(String id, String userId, String roomId, DateRange dates) {
        this.id = id;
        this.userId = userId;
        this.roomId = roomId;
        this.dates = dates;
        this.status = ReservationStatus.CONFIRMED;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getRoomId() {
        return roomId;
    }

    public DateRange getDates() {
        return dates;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void cancel() {
        this.status = ReservationStatus.CANCELLED;
    }

    public boolean isActive() {
        return status == ReservationStatus.CONFIRMED;
    }
}
