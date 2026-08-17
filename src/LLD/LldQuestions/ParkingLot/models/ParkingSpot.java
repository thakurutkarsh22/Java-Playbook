package LLD.LldQuestions.ParkingLot.models;

/*
 * A single physical slot in the lot.
 *
 *   id          : "F2-R3-S07" -> floor 2, row 3, slot 7 (purely human-readable)
 *   size        : largest vehicle that fits here; up-grades allowed (BIKE in CAR).
 *   occupiedBy  : null when free, the parked vehicle's plate when in-use.
 *
 * The `occupiedBy` field is read/written only under the per-floor lock in
 * ParkingFloor (see ParkingFloor.java for the rationale), so a plain reference
 * is safe -- no volatile, no AtomicReference. Centralizing the synchronization
 * in the floor keeps this class trivial to reason about.
 */
public class ParkingSpot {

    private final String id;
    private final VehicleSize size;
    private String occupiedBy;          // plate of the parked vehicle, or null

    public ParkingSpot(String id, VehicleSize size) {
        this.id = id;
        this.size = size;
    }

    public String getId() {
        return id;
    }

    public VehicleSize getSize() {
        return size;
    }

    public boolean isFree() {
        return occupiedBy == null;
    }

    public boolean canFit(VehicleSize vehicleSize) {
        // Spot's capacity must be >= vehicle's size. BIKE fits in CAR or TRUCK
        // spots; CAR fits in CAR or TRUCK; TRUCK only in TRUCK.
        return size.compareTo(vehicleSize) >= 0;
    }

    public void occupy(String plate) {
        this.occupiedBy = plate;
    }

    public void release() {
        this.occupiedBy = null;
    }

    public String getOccupiedBy() {
        return occupiedBy;
    }
}
