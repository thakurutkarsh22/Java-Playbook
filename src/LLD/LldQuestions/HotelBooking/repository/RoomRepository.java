package LLD.LldQuestions.HotelBooking.repository;

import LLD.LldQuestions.HotelBooking.models.Room;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RoomRepository {

    private final Map<String, Room> byId = new ConcurrentHashMap<>();

    public void add(Room room) {
        byId.put(room.getId(), room);
    }

    public Room get(String id) {
        return byId.get(id);
    }

    public List<Room> all() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }
}
