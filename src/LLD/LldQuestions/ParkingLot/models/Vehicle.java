package LLD.LldQuestions.ParkingLot.models;

/*
 * A parked or parking vehicle. The plate is the natural primary key; the size
 * decides which spot types are admissible.
 *
 * This is a pure data class on purpose -- no locking, no behavior. The whole
 * "dumb models, smart services" rule from the LLD README applies here.
 */
public final class Vehicle {

    private final String plate;
    private final VehicleSize size;

    public Vehicle(String plate, VehicleSize size) {
        this.plate = plate;
        this.size = size;
    }

    public String getPlate() {
        return plate;
    }

    public VehicleSize getSize() {
        return size;
    }

    @Override
    public String toString() {
        return plate + "(" + size + ")";
    }
}
