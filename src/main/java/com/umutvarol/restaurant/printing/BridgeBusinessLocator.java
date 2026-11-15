package com.umutvarol.restaurant.printing;

import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.business.BusinessLocator;
import com.umutvarol.restaurant.common.crypto.SecretTokens;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** A print bridge's calls belong to the business of its token. */
@Component
class BridgeBusinessLocator implements BusinessLocator {

    static final String HEADER = "X-Bridge-Token";

    private final PrintBridgeRepository bridges;

    BridgeBusinessLocator(PrintBridgeRepository bridges) {
        this.bridges = bridges;
    }

    @Override
    public Optional<Long> locate(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/api/v1/bridge/")) {
            return Optional.empty();
        }
        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank() || token.length() > 200) {
            return Optional.empty();
        }
        String hash = SecretTokens.hash(token);
        return BusinessContext.acrossAll(() -> bridges.findByTokenHash(hash).filter(PrintBridge::isActive)
                .map(PrintBridge::getBusinessId));
    }
}
