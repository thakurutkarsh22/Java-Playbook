package LLD.LldQuestions.HotelBooking.exceptions;

/*
 * Thrown when the requested room is already reserved for a range that
 * overlaps the requested range. Unchecked because the caller's only
 * meaningful response is to offer the user an alternative.
 */
public class RoomUnavailableException extends RuntimeException {
    public RoomUnavailableException(String message) {
        super(message);
    }
}
