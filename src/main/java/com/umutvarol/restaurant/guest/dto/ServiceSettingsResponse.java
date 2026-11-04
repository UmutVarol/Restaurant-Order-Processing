package com.umutvarol.restaurant.guest.dto;

import com.umutvarol.restaurant.guest.ServiceMode;
import com.umutvarol.restaurant.guest.ServiceSettings;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record ServiceSettingsResponse(ServiceMode mode, @Nullable BigDecimal autoAcceptLimit,
                                      boolean firstCartNeedsStaff, @Nullable Instant updatedAt,
                                      @Nullable String updatedBy) {

    public static ServiceSettingsResponse from(ServiceSettings settings) {
        return new ServiceSettingsResponse(settings.getMode(), settings.getAutoAcceptLimit(),
                settings.isFirstCartNeedsStaff(), settings.getUpdatedAt(), settings.getUpdatedBy());
    }
}
