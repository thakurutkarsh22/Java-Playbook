package LLD.LldQuestions.HotelBooking.models;

import java.time.LocalDate;

/*
 * Half-open date interval: [checkIn, checkOut).
 *
 * The half-open convention is the industry default for hotels: "check in
 * the 12th, check out the 14th" means you occupy the room on the nights
 * of the 12th and 13th, NOT the 14th. So the next guest can check in on
 * the 14th in the same room. The overlap predicate respects this.
 *
 * Two ranges [a, b) and [c, d) overlap iff  a < d  AND  c < b.
 *
 * Why immutable?
 *   - DateRange is the key to dedup-and-conflict logic, and we cache it on
 *     Reservation objects. If a caller could mutate it after the booking
 *     was made, conflict checks would silently break.
 */
public final class DateRange {

    private final LocalDate checkIn;
    private final LocalDate checkOut;

    public DateRange(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null || checkOut == null) {
            throw new IllegalArgumentException("dates must be non-null");
        }
        if (!checkIn.isBefore(checkOut)) {
            throw new IllegalArgumentException(
                    "checkIn must be strictly before checkOut: " + checkIn + " -> " + checkOut);
        }
        this.checkIn = checkIn;
        this.checkOut = checkOut;
    }

    public LocalDate getCheckIn() {
        return checkIn;
    }

    public LocalDate getCheckOut() {
        return checkOut;
    }

    /*
     * Standard interval overlap test, on half-open intervals.
     *
     * Truth table (this=[a,b), other=[c,d)):
     *     b <= c        ── this ends before other starts   -> no overlap
     *     d <= a        ── other ends before this starts   -> no overlap
     *     else                                              -> overlap
     */
    public boolean overlaps(DateRange other) {
        return this.checkIn.isBefore(other.checkOut)
                && other.checkIn.isBefore(this.checkOut);
    }

    public int nights() {
        return (int) java.time.temporal.ChronoUnit.DAYS.between(checkIn, checkOut);
    }

    @Override
    public String toString() {
        return "[" + checkIn + ", " + checkOut + ")";
    }
}
