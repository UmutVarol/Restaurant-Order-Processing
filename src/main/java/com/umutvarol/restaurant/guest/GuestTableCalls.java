package com.umutvarol.restaurant.guest;

import com.umutvarol.restaurant.floor.TableCalls;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Open calls, bill requests and carts, as the floor plan's "waiting since" for each table. */
@Component
@Transactional(readOnly = true)
class GuestTableCalls implements TableCalls {

    private final GuestRequestRepository requests;

    GuestTableCalls(GuestRequestRepository requests) {
        this.requests = requests;
    }

    @Override
    public Map<Long, Instant> waitingSince() {
        Map<Long, Instant> since = new LinkedHashMap<>();
        // oldest first, so the first request seen at a table is the one it has waited on longest
        for (GuestRequest request : requests.findByStatusOrderByCreatedAtAsc(GuestRequestStatus.OPEN)) {
            if (request.getTable() != null) {
                since.putIfAbsent(request.getTable().getId(), request.getCreatedAt());
            }
        }
        return since;
    }
}
