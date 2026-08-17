package LLD.LldQuestions.ElevatorSystem.models;

/*
 * A single floor-button press.
 *
 *   HALL CALL : the user is ON `floor`, wants to go in `direction`
 *               (this is the "up/down arrow" button outside the elevator).
 *               destinationFloor is unknown until the user steps in.
 *
 *   CAR CALL  : the user is INSIDE the elevator, wants to go to
 *               `destinationFloor`. direction is implied.
 *
 * We model both as one Request with `kind`. Real systems sometimes split
 * them, but the scheduler treats them similarly.
 */
public final class Request {

    public enum Kind {
        HALL_CALL,
        CAR_CALL
    }

    private final Kind kind;
    private final int floor;             // origin for HALL_CALL, destination for CAR_CALL
    private final Direction direction;   // requested travel direction (HALL); car's current dir (CAR)
    private final long createdAtNanos;

    public Request(Kind kind, int floor, Direction direction) {
        this.kind = kind;
        this.floor = floor;
        this.direction = direction;
        this.createdAtNanos = System.nanoTime();
    }

    public Kind getKind() {
        return kind;
    }

    public int getFloor() {
        return floor;
    }

    public Direction getDirection() {
        return direction;
    }

    public long getCreatedAtNanos() {
        return createdAtNanos;
    }

    @Override
    public String toString() {
        return kind + "(floor=" + floor + ", dir=" + direction + ")";
    }
}
