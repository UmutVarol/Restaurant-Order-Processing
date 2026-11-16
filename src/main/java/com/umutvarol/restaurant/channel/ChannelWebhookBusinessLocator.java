package com.umutvarol.restaurant.channel;

import com.umutvarol.restaurant.business.Business;
import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.business.BusinessLocator;
import com.umutvarol.restaurant.business.BusinessRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * A platform's order for one business arrives at {@code /api/v1/channels/{platform}/webhook/{business code}}: the
 * business is the one with that code. Its own webhook secret then decides whether the order is genuine.
 */
@Component
class ChannelWebhookBusinessLocator implements BusinessLocator {

    private static final Pattern PATH = Pattern.compile("^/api/v1/channels/[a-z]+/webhook/([a-z0-9-]{2,40})$");

    private final BusinessRepository businesses;

    ChannelWebhookBusinessLocator(BusinessRepository businesses) {
        this.businesses = businesses;
    }

    @Override
    public Optional<Long> locate(HttpServletRequest request) {
        Matcher match = PATH.matcher(request.getRequestURI());
        if (!match.matches()) {
            return Optional.empty();
        }
        return BusinessContext.acrossAll(() -> businesses.findBySlug(match.group(1))).map(Business::getId);
    }
}
