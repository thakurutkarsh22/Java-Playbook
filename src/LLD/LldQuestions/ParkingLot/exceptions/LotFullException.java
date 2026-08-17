package LLD.LldQuestions.ParkingLot.exceptions;

/*
 * Thrown when every floor in the lot has zero acceptable free spots for the
 * given vehicle. Unchecked because the only correct caller behavior is "show
 * a 'LOT FULL' display and turn the car away" -- there's nothing actionable
 * to do programmatically.
 */
public class LotFullException extends RuntimeException {
    public LotFullException(String message) {
        super(message);
    }
}
