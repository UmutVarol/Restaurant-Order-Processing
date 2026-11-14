package com.umutvarol.restaurant.guest;

import com.umutvarol.restaurant.guest.dto.ServiceSettingsRequest;
import com.umutvarol.restaurant.guest.dto.ServiceSettingsResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and changes how the restaurant takes orders. */
@Service
@Transactional
public class ServiceSettingsService {

    private static final Logger audit = LoggerFactory.getLogger("guest.audit");

    private final ServiceSettingsRepository settings;
    private final Clock clock;

    public ServiceSettingsService(ServiceSettingsRepository settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ServiceSettingsResponse get() {
        return ServiceSettingsResponse.from(current());
    }

    public ServiceSettingsResponse update(ServiceSettingsRequest request, String actor) {
        long business = business();
        ServiceSettings current = settings.findById(business).orElseGet(() -> settings.save(ServiceSettings.defaults(business)));
        BigDecimal limit = request.autoAcceptLimit() == null ? null
                : request.autoAcceptLimit().setScale(2, RoundingMode.HALF_UP);
        current.change(request.mode(), limit, request.firstCartNeedsStaff(), clock.instant(), actor);
        audit.info("guest.service_settings mode={} limit={} first_cart_needs_staff={} by={}", request.mode(), limit,
                request.firstCartNeedsStaff(), actor);
        return ServiceSettingsResponse.from(current);
    }

    /** This business's settings; a business that never changed them works with the defaults (not stored until changed). */
    ServiceSettings current() {
        long business = business();
        return settings.findById(business).orElseGet(() -> ServiceSettings.defaults(business));
    }

    private static long business() {
        return java.util.Optional.ofNullable(com.umutvarol.restaurant.business.BusinessContext.current()).orElse(1L);
    }
}
