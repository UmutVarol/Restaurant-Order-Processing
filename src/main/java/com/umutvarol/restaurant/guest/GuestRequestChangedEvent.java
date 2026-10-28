package com.umutvarol.restaurant.guest;

import java.time.Instant;

/** Published when a guest asks for something or staff deal with it, so every till's inbox updates live. */
public record GuestRequestChangedEvent(Long id, GuestRequestKind kind, GuestRequestStatus status, String tableCode,
                                       Instant occurredAt) {

    static GuestRequestChangedEvent of(GuestRequest request, Instant at) {
        return new GuestRequestChangedEvent(request.getId(), request.getKind(), request.getStatus(),
                request.getTable().getCode(), at);
    }
}
