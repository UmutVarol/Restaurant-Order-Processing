package com.umutvarol.restaurant.floor;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A table was marked for clearing or cleared; open floor and service screens refresh. */
public record TableChangedEvent(Long tableId, String tableCode, @Nullable Instant needsClearingSince, Instant occurredAt) {
}
