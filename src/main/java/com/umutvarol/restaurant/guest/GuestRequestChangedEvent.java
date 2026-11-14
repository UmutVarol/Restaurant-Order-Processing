package com.umutvarol.restaurant.guest;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.umutvarol.restaurant.business.BranchScoped;
import java.time.Instant;

/** Published when a guest asks for something or staff deal with it, so every till's inbox updates live. */
public record GuestRequestChangedEvent(Long id, GuestRequestKind kind, GuestRequestStatus status, String tableCode,
                                       Instant occurredAt, @JsonIgnore @Nullable Long branchId) implements BranchScoped {

    static GuestRequestChangedEvent of(GuestRequest request, Instant at) {
        return new GuestRequestChangedEvent(request.getId(), request.getKind(), request.getStatus(),
                request.getTable().getCode(), at, request.getBranchId());
    }
}
