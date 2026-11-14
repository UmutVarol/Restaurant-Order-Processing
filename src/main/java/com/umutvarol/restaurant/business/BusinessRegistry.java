package com.umutvarol.restaurant.business;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opens new businesses: the only place a business is created, with its address checked and its trial started. */
@Service
public class BusinessRegistry {

    public static final Duration TRIAL = Duration.ofDays(14);
    /** Addresses that belong to Sofra itself, never to a customer. */
    static final Set<String> RESERVED = Set.of("www", "app", "api", "admin", "default", "demo", "mail", "status",
            "help", "support", "sofra", "billing", "login", "signup", "static", "assets", "docs", "blog");

    private final BusinessRepository businesses;
    private final TransactionTemplate tx;
    private final Clock clock;

    BusinessRegistry(BusinessRepository businesses, PlatformTransactionManager transactions, Clock clock) {
        this.businesses = businesses;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** A new business on a 14-day trial of the full product. Runs on its own, outside any business. */
    public Business open(String name, String slug, String timeZone, @Nullable String ownerEmail) {
        String address = slug.strip().toLowerCase(Locale.ROOT);
        if (!address.matches("[a-z0-9](?:[a-z0-9-]{1,38})[a-z0-9]") || RESERVED.contains(address)) {
            throw new BusinessRuleException("BUSINESS_ADDRESS_INVALID",
                    "Use 3 to 40 lower-case letters, digits and dashes", Map.of("slug", address));
        }
        return BusinessContext.acrossAll(() -> tx.execute(status -> {
            if (businesses.findBySlug(address).isPresent()) {
                throw new InvalidStateException("BUSINESS_ADDRESS_TAKEN", "That address is already taken", Map.of("slug", address));
            }
            Business business = new Business(address, name.strip(), timeZone, Business.Plan.BUSINESS,
                    Business.Status.TRIAL, clock.instant().plus(TRIAL), clock.instant());
            business.setOwnerEmail(ownerEmail);
            return businesses.save(business);
        }));
    }

    /** Undoes {@link #open} when setting the new business up failed half-way. */
    public void discard(long id) {
        BusinessContext.acrossAll(() -> tx.execute(status -> {
            businesses.deleteById(id);
            return null;
        }));
    }

    /** An address from a name: "Kebapçı Mehmet Usta" becomes "kebapci-mehmet-usta". */
    public static String suggestAddress(String name) {
        String ascii = Normalizer.normalize(name.toLowerCase(new Locale("tr")).replace('ı', 'i'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String slug = ascii.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return slug.length() > 40 ? slug.substring(0, 40).replaceAll("-+$", "") : slug;
    }
}
