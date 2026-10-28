package com.umutvarol.restaurant.security.dto;

import com.umutvarol.restaurant.security.PosDevice;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record DeviceResponse(Long id, String name, String createdBy, Instant createdAt, @Nullable Instant lastSeenAt,
                             boolean active) {

    public static DeviceResponse from(PosDevice device) {
        return new DeviceResponse(device.getId(), device.getName(), device.getCreatedBy(), device.getCreatedAt(),
                device.getLastSeenAt(), device.isActive());
    }
}
