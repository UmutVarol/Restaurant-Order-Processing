package com.umutvarol.restaurant.bootstrap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.umutvarol.restaurant.business.Business;
import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.business.BusinessRegistry;
import com.umutvarol.restaurant.business.BusinessRepository;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.common.error.TooManyRequestsException;
import com.umutvarol.restaurant.menu.DietaryTag;
import com.umutvarol.restaurant.menu.MenuCategory;
import com.umutvarol.restaurant.menu.MenuItem;
import com.umutvarol.restaurant.menu.MenuItemRepository;
import com.umutvarol.restaurant.security.StaffService;
import com.umutvarol.restaurant.security.dto.TokenResponse;
import com.umutvarol.restaurant.table.DiningTable;
import com.umutvarol.restaurant.table.DiningTableRepository;
import com.umutvarol.restaurant.table.TableZone;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-up for Sofra Cloud: a new business on a trial, its owner's account, and something to start from (twelve
 * tables, a short sample menu), so the owner sees a working restaurant a minute after deciding to try. Off unless
 * {@code restaurant.cloud.signup-enabled}; a client may open only a few businesses an hour.
 */
@Service
public class SignupService {

    private static final Logger audit = LoggerFactory.getLogger("business.audit");

    public record SignupRequest(
            @NotBlank @Size(min = 2, max = 80) String businessName,
            @Nullable @Pattern(regexp = "^[a-z0-9-]{3,40}$") String address,
            @NotBlank @Email @Size(max = 120) String email,
            @NotBlank @Size(max = 80) String ownerName,
            @NotBlank @Pattern(regexp = "^[a-z0-9._-]{3,50}$") String username,
            @NotBlank @Size(max = 128) String password) {

        @Override
        public String toString() {
            return "SignupRequest[businessName=" + businessName + ", address=" + address + ", username=" + username + "]";
        }
    }

    public record SignupResponse(String address, String url, Instant trialEndsAt, TokenResponse session) {
    }

    public record AddressCheck(String address, boolean free) {
    }

    private final BusinessRegistry registry;
    private final BusinessRepository businesses;
    private final StaffService staff;
    private final DiningTableRepository tables;
    private final MenuItemRepository menu;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final String baseDomain;
    private final int perClientPerHour;
    private final Cache<String, AtomicInteger> recent = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1))
            .maximumSize(100_000).build();

    SignupService(BusinessRegistry registry, BusinessRepository businesses, StaffService staff,
                  DiningTableRepository tables, MenuItemRepository menu, PlatformTransactionManager transactions,
                  @Value("${restaurant.cloud.signup-enabled:false}") boolean enabled,
                  @Value("${restaurant.cloud.base-domain:}") String baseDomain,
                  @Value("${restaurant.cloud.signups-per-client-per-hour:5}") int perClientPerHour) {
        this.perClientPerHour = perClientPerHour;
        this.registry = registry;
        this.businesses = businesses;
        this.staff = staff;
        this.tables = tables;
        this.menu = menu;
        this.tx = new TransactionTemplate(transactions);
        this.enabled = enabled;
        this.baseDomain = baseDomain;
    }

    public SignupResponse signUp(SignupRequest request, String client) {
        requireEnabled();
        if (recent.get(client, key -> new AtomicInteger()).incrementAndGet() > perClientPerHour) {
            throw new TooManyRequestsException("SIGNUP_LIMIT", "Too many new businesses from here. Try again later.",
                    Duration.ofHours(1));
        }
        String address = request.address() != null ? request.address() : BusinessRegistry.suggestAddress(request.businessName());
        Business business = registry.open(request.businessName(), address, "Europe/Istanbul");
        try {
            TokenResponse session = BusinessContext.call(business.getId(), () -> tx.execute(status -> {
                TokenResponse owner = staff.createOwner(request.username(), request.ownerName(), request.password());
                sampleTables();
                sampleMenu();
                return owner;
            }));
            audit.info("business.signup id={} address={} owner={}", business.getId(), business.getSlug(), request.username());
            return new SignupResponse(business.getSlug(), url(business.getSlug()), business.getTrialEndsAt(), session);
        } catch (RuntimeException failure) {
            registry.discard(business.getId());
            throw failure;
        }
    }

    public AddressCheck checkAddress(String name, @Nullable String slug) {
        requireEnabled();
        String address = slug != null && !slug.isBlank() ? slug.strip().toLowerCase() : BusinessRegistry.suggestAddress(name);
        boolean free = address.length() >= 3 && BusinessContext.acrossAll(() -> businesses.findBySlug(address).isEmpty());
        return new AddressCheck(address, free);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ResourceNotFoundException("page", "signup");
        }
    }

    private String url(String slug) {
        return baseDomain.isBlank() ? "/" : "https://" + slug + "." + baseDomain;
    }

    private void sampleTables() {
        for (int n = 1; n <= 12; n++) {
            tables.save(new DiningTable(String.valueOf(n), n <= 2 ? 2 : n <= 9 ? 4 : 6, n <= 8 ? TableZone.INDOOR : TableZone.TERRACE));
        }
    }

    /** A few dishes to try the till with; the owner replaces them with their own. */
    private void sampleMenu() {
        record Dish(String name, String description, MenuCategory category, String price, String icon, int minutes) {
        }
        List<Dish> dishes = List.of(
                new Dish("Mercimek Çorbası", "Örnek ürün: kendi menünüzle değiştirin", MenuCategory.STARTER, "90.00", "🥣", 5),
                new Dish("Humus", "Örnek ürün", MenuCategory.STARTER, "120.00", "🥙", 5),
                new Dish("Izgara Köfte", "Örnek ürün", MenuCategory.MAIN, "320.00", "🍖", 15),
                new Dish("Tavuk Şiş", "Örnek ürün", MenuCategory.MAIN, "280.00", "🍢", 15),
                new Dish("Sebzeli Makarna", "Örnek ürün", MenuCategory.MAIN, "240.00", "🍝", 12),
                new Dish("Sütlaç", "Örnek ürün", MenuCategory.DESSERT, "110.00", "🍮", 3),
                new Dish("Ayran", "Örnek ürün", MenuCategory.DRINK, "40.00", "🥛", 1),
                new Dish("Çay", "Örnek ürün", MenuCategory.DRINK, "25.00", "🍵", 1),
                new Dish("Türk Kahvesi", "Örnek ürün", MenuCategory.DRINK, "70.00", "☕", 4));
        for (Dish d : dishes) {
            menu.save(new MenuItem(d.name(), d.description(), d.category(), new BigDecimal(d.price()), d.icon(), d.minutes(),
                    false, Set.<DietaryTag>of()));
        }
    }
}
