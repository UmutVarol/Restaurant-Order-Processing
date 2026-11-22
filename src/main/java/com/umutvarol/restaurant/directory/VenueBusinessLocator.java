package com.umutvarol.restaurant.directory;

import com.umutvarol.restaurant.business.Business;
import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.business.BusinessLocator;
import com.umutvarol.restaurant.business.BusinessRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** A guest on a restaurant's public page in the guest app belongs to the business named in its address. */
@Component
class VenueBusinessLocator implements BusinessLocator {

    private static final Pattern PATH = Pattern.compile("^/api/v1/public/venue/([a-z0-9-]{2,40})/[a-z0-9-]{2,20}(/.*)?$");

    private final BusinessRepository businesses;

    VenueBusinessLocator(BusinessRepository businesses) {
        this.businesses = businesses;
    }

    @Override
    public Optional<Long> locate(HttpServletRequest request) {
        Matcher match = PATH.matcher(request.getRequestURI());
        if (!match.matches()) {
            return Optional.empty();
        }
        return BusinessContext.acrossAll(() -> businesses.findBySlug(match.group(1)))
                .filter(b -> b.getStatus() != Business.Status.CANCELLED)
                .map(Business::getId);
    }
}
