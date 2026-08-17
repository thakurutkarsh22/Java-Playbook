package LLD.LldQuestions.ParkingLot.exceptions;

/*
 * Thrown when an exit gate presents a ticket id that is unknown OR whose
 * plate does not match what's currently parked in the claimed spot. The
 * second case defends against ticket swapping at the exit barrier.
 */
public class InvalidTicketException extends RuntimeException {
    public InvalidTicketException(String message) {
        super(message);
    }
}
