package com.umutvarol.restaurant.reservation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.umutvarol.restaurant.business.BranchScoped;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A booking or walk-in was added or moved on; open reservation and floor screens refresh. Carries no contact data. */
public record ReservationChangedEvent(Long reservationId, ReservationStatus status, @Nullable Long tableId,
                                      Instant occurredAt, @JsonIgnore @Nullable Long branchId) implements BranchScoped {
}
