package com.umutvarol.restaurant.compliance;

import com.umutvarol.restaurant.business.Branch;
import com.umutvarol.restaurant.business.BranchContext;
import com.umutvarol.restaurant.business.BranchRepository;
import com.umutvarol.restaurant.business.Business;
import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.business.BusinessRepository;
import com.umutvarol.restaurant.common.config.RestaurantProperties;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.menu.MenuCategory;
import com.umutvarol.restaurant.menu.MenuService;
import com.umutvarol.restaurant.menu.dto.MenuItemResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A branch's price list as the Fiyat Etiketi Yönetmeliği asks for it: every dish it sells with its price in Turkish
 * lira, VAT included, and the date the prices last changed. Printed at the door and on the tables, and exported for
 * the Ministry of Trade's electronic price list.
 */
@Service
public class PriceListService {

    /**
     * @param lastChangedAt the latest price change among the dishes; the printed list shows it
     * @param listed        whether the branch appears in the guest app, so its page can be linked from the list
     */
    public record PriceList(String business, String slug, Long branchId, String branch, String branchCode,
                            @Nullable String address, boolean listed, boolean chain, String timeZone,
                            @Nullable Instant lastChangedAt, Instant generatedAt, List<Item> items) {
    }

    public record Item(MenuCategory category, String name, BigDecimal price, BigDecimal vatRate, boolean available,
                       @Nullable Instant priceChangedAt) {
    }

    private final MenuService menu;
    private final BranchRepository branches;
    private final BusinessRepository businesses;
    private final RestaurantProperties restaurant;
    private final Clock clock;

    PriceListService(MenuService menu, BranchRepository branches, BusinessRepository businesses,
                     RestaurantProperties restaurant, Clock clock) {
        this.menu = menu;
        this.branches = branches;
        this.businesses = businesses;
        this.restaurant = restaurant;
        this.clock = clock;
    }

    /** The list of one branch: the one asked for, else the branch this request works in, else the first open one. */
    @Transactional(readOnly = true)
    public PriceList of(@Nullable Long branchId) {
        Branch branch = branch(branchId);
        Long businessId = BusinessContext.current(); // read here: inside acrossAll there is no current business
        Business owner = BusinessContext.acrossAll(() -> businesses.findById(businessId))
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", businessId));
        // the first business of a single-restaurant install is named in its configuration
        String name = owner.getId() == 1L && "Sofra".equals(owner.getName()) ? restaurant.name() : owner.getName();
        // in Turkish alphabetical order: İskender after Humus, Şalgam after Su
        java.text.Collator turkish = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("tr"));
        List<Item> items = menu.list(null, null, true, branch.getId()).stream()
                .sorted(Comparator.comparing(MenuItemResponse::category).thenComparing(MenuItemResponse::name, turkish))
                .map(dish -> new Item(dish.category(), dish.name(), dish.price(), dish.vatRate(), dish.available(), dish.priceChangedAt()))
                .toList();
        Instant last = items.stream().map(Item::priceChangedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        boolean chain = BranchContext.acrossBranches(() -> branches.countByActiveTrue()) > 1;
        return new PriceList(name, owner.getSlug(), branch.getId(), branch.getName(), branch.getCode(), branch.getAddress(),
                branch.isListed(), chain, zone(owner).getId(), last, clock.instant(), items);
    }

    private Branch branch(@Nullable Long branchId) {
        Long id = branchId != null ? branchId : BranchContext.current();
        List<Branch> open = BranchContext.acrossBranches(() -> branches.findAllByOrderByIdAsc()).stream()
                .filter(Branch::isActive).toList();
        if (id == null) {
            return open.stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("branch", "open"));
        }
        // someone who works in one branch sees only its list
        Long home = BranchContext.current();
        if (home != null && !home.equals(id)) {
            throw new ResourceNotFoundException("branch", id);
        }
        return open.stream().filter(branch -> branch.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("branch", id));
    }

    private ZoneId zone(Business owner) {
        try {
            return ZoneId.of(owner.getTimeZone());
        } catch (RuntimeException unknown) {
            return restaurant.timeZone();
        }
    }
}
