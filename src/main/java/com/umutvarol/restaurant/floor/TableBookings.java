package com.umutvarol.restaurant.floor;

import com.umutvarol.restaurant.floor.TableStatusResponse.Party;
import java.util.Map;

/**
 * Who sits at, or is booked for, each table. Implemented by the reservations feature, so the floor plan can show
 * it without depending on it.
 */
public interface TableBookings {

    TableBookings NONE = new TableBookings() {
        @Override
        public Map<Long, Party> seated() {
            return Map.of();
        }

        @Override
        public Map<Long, Party> nextBookings() {
            return Map.of();
        }

        @Override
        public boolean holdsTable(Long tableId) {
            return false;
        }
    };

    /** The party seated at each table, by table id. */
    Map<Long, Party> seated();

    /** Each table's next booking in the coming hours, by table id. */
    Map<Long, Party> nextBookings();

    /** Whether a party is seated at the table or has booked it for later. */
    boolean holdsTable(Long tableId);
}
