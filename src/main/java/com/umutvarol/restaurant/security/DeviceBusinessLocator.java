package com.umutvarol.restaurant.security;

import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.business.BusinessLocator;
import com.umutvarol.restaurant.common.crypto.SecretTokens;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** A registered till belongs to the business that registered it: its staff switch by PIN there. */
@Component
class DeviceBusinessLocator implements BusinessLocator {

    private final PosDeviceRepository devices;

    DeviceBusinessLocator(PosDeviceRepository devices) {
        this.devices = devices;
    }

    @Override
    public Optional<Long> locate(HttpServletRequest request) {
        String token = request.getHeader(AuthController.DEVICE_HEADER);
        if (token == null || token.isBlank() || token.length() > 200) {
            return Optional.empty();
        }
        String hash = SecretTokens.hash(token);
        return BusinessContext.acrossAll(() -> devices.findByTokenHash(hash).filter(PosDevice::isActive).map(PosDevice::getBusinessId));
    }
}
