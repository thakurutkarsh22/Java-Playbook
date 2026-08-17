package LLD.LldQuestions.ParkingLot.models;

/*
 * Vehicle sizes, in *ascending* capacity order.
 *
 * Ordering matters: a BIKE may park in a CAR slot if no BIKE slot is free, but
 * a CAR may NEVER park in a BIKE slot. The compareTo() ordering on the enum
 * lets the allocator say "any spot >= the vehicle's size is acceptable", which
 * makes the up-grade logic a one-line `spotSize.compareTo(vehicleSize) >= 0`.
 *
 * We deliberately keep this small (3 values). Real systems often have
 * MOTORBIKE / SCOOTER / SEDAN / SUV / TRUCK -- the design pattern is the same,
 * the table just gets wider.
 */
public enum VehicleSize {
    BIKE,
    CAR,
    TRUCK
}
