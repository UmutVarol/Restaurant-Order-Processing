package com.umutvarol.restaurant.menu;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.menu.dto.MenuItemRequest;
import com.umutvarol.restaurant.menu.dto.MenuItemResponse;
import com.umutvarol.restaurant.menu.dto.OptionGroupRequest;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class MenuService {

    private static final Comparator<MenuItem> MENU_ORDER = Comparator
            .comparing(MenuItem::getCategory)
            .thenComparing(MenuItem::isPopular, Comparator.reverseOrder())
            .thenComparing(MenuItem::getName, String.CASE_INSENSITIVE_ORDER);

    private final MenuItemRepository repository;
    private final StationService stations;
    private final ApplicationEventPublisher events;

    private final BranchMenu branchMenu;
    private final BranchMenuItemRepository branchOverrides;
    private final com.umutvarol.restaurant.business.BranchRepository branches;
    private final java.time.Clock clock;

    public MenuService(MenuItemRepository repository, StationService stations, ApplicationEventPublisher events,
                       BranchMenu branchMenu, BranchMenuItemRepository branchOverrides,
                       com.umutvarol.restaurant.business.BranchRepository branches, java.time.Clock clock) {
        this.branchMenu = branchMenu;
        this.branchOverrides = branchOverrides;
        this.branches = branches;
        this.clock = clock;
        this.repository = repository;
        this.stations = stations;
        this.events = events;
    }

    /** The menu as the branch this request works in sells it; the central menu for someone working in all branches. */
    @Transactional(readOnly = true)
    public List<MenuItemResponse> list(MenuCategory category, String query, boolean includeUnavailable) {
        return list(category, query, includeUnavailable, com.umutvarol.restaurant.business.BranchContext.current());
    }

    /** The menu as one branch sells it (null: the central menu). */
    @Transactional(readOnly = true)
    public List<MenuItemResponse> list(MenuCategory category, String query, boolean includeUnavailable, Long branchId) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        java.util.Map<Long, BranchMenuItem> branch = branchMenu.of(branchId);
        return repository.findByArchivedFalse().stream()
                .filter(item -> category == null || item.getCategory() == category)
                .filter(item -> includeUnavailable || BranchMenu.orderable(item, branch.get(item.getId())))
                .filter(item -> needle.isEmpty() || matches(item, needle))
                .sorted(MENU_ORDER)
                .map(item -> MenuItemResponse.from(item, branch.get(item.getId())))
                .toList();
    }

    /** One dish across the open branches: the central price and each branch's own, where it has one. */
    public record BranchPrice(Long branchId, String branch, java.math.BigDecimal centralPrice, java.math.BigDecimal price,
                              Boolean available, boolean centralAvailable) {
    }

    @Transactional(readOnly = true)
    public List<BranchPrice> branchPrices(Long itemId) {
        MenuItem item = find(itemId);
        java.util.Map<Long, BranchMenuItem> own = branchOverrides.findByMenuItemId(itemId).stream()
                .collect(java.util.stream.Collectors.toMap(BranchMenuItem::getBranchId, java.util.function.Function.identity()));
        return com.umutvarol.restaurant.business.BranchContext.acrossBranches(() -> branches.findAllByOrderByIdAsc()).stream()
                .filter(com.umutvarol.restaurant.business.Branch::isActive)
                .map(branch -> {
                    BranchMenuItem differs = own.get(branch.getId());
                    return new BranchPrice(branch.getId(), branch.getName(), item.getPrice(),
                            differs == null ? null : differs.getPrice(), differs == null ? null : differs.getAvailable(), item.isAvailable());
                }).toList();
    }

    /** Sets one branch's own price and sale state of a dish; nulls go back to the central menu. */
    public List<BranchPrice> setBranchPrice(Long itemId, Long branchId, java.math.BigDecimal price, Boolean available, String actor) {
        find(itemId);
        com.umutvarol.restaurant.business.BranchContext.acrossBranches(() -> branches.findById(branchId))
                .filter(com.umutvarol.restaurant.business.Branch::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("branch", branchId));
        java.util.Optional<BranchMenuItem> existing = branchOverrides.findByBranchIdAndMenuItemId(branchId, itemId);
        if (price == null && available == null && existing.map(row -> row.getPrice() == null).orElse(true)) {
            existing.ifPresent(branchOverrides::delete); // the branch sells it like the central menu again
        } else {
            // a branch going back to the central price keeps its row: the price list shows when its price changed
            BranchMenuItem row = existing.orElseGet(() -> new BranchMenuItem(branchId, itemId));
            row.change(price == null ? null : com.umutvarol.restaurant.common.money.Money.normalize(price), available, clock.instant(), actor);
            branchOverrides.save(row);
        }
        return branchPrices(itemId);
    }

    @Transactional(readOnly = true)
    public MenuItemResponse get(Long id) {
        return MenuItemResponse.from(find(id));
    }

    public MenuItemResponse create(MenuItemRequest request) {
        refuseCharges(request);
        if (repository.existsByNameIgnoreCase(request.name().strip())) {
            throw nameTaken(request.name());
        }
        MenuItem item = new MenuItem(request.name(), request.description(), request.category(), request.price(),
                request.icon(), request.prepTimeMinutesOrDefault(), request.popularOrDefault(), request.tags());
        item.priceChanged(clock.instant());
        if (request.vatRate() != null) {
            item.changeVatRate(request.vatRate());
        }
        item.replaceOptions(specs(request));
        // Stored explicitly, so the station counts as in use and cannot be removed from under the dish.
        assignStation(item, request.stationCode() != null ? request.stationCode() : Station.defaultCode(item.getCategory()));
        item.requireStaffApproval(Boolean.TRUE.equals(request.needsApproval()));
        if (request.allergens() != null) {
            item.declareAllergens(request.allergens());
        }
        return MenuItemResponse.from(repository.saveAndFlush(item));
    }

    public MenuItemResponse update(Long id, MenuItemRequest request) {
        refuseCharges(request);
        MenuItem item = find(id);
        if (repository.existsByNameIgnoreCaseAndIdNot(request.name().strip(), id)) {
            throw nameTaken(request.name());
        }
        boolean repriced = item.getPrice().compareTo(request.price()) != 0;
        item.update(request.name(), request.description(), request.category(), request.price(), request.icon(),
                request.prepTimeMinutesOrDefault(), request.popularOrDefault(), request.tags());
        if (repriced) {
            item.priceChanged(clock.instant());
        }
        // left out of the request (an older till), the rate stays as it is
        if (request.vatRate() != null) {
            item.changeVatRate(request.vatRate());
        }
        if (request.stationCode() != null) {
            assignStation(item, request.stationCode());
        }
        if (request.needsApproval() != null) {
            item.requireStaffApproval(request.needsApproval());
        }
        // Left out of the request (an older till), the declared allergens stay as they are.
        if (request.allergens() != null) {
            item.declareAllergens(request.allergens());
        }
        // Left out of the request, the options stay as they are; an empty list removes them.
        if (request.optionGroups() != null) {
            item.replaceOptions(specs(request));
            repository.flush();
        }
        return MenuItemResponse.from(item);
    }

    /** No service, cover or table charge under the name of a dish or an option (Fiyat Etiketi Yönetmeliği). */
    private static void refuseCharges(MenuItemRequest request) {
        ExtraCharges.refuse(request.name());
        if (request.optionGroups() != null) {
            request.optionGroups().forEach(group -> {
                ExtraCharges.refuse(group.name());
                group.choices().forEach(choice -> ExtraCharges.refuse(choice.name()));
            });
        }
    }

    private void assignStation(MenuItem item, String code) {
        stations.requireExisting(Set.of(code));
        item.assignStation(code);
    }

    private static List<OptionGroupSpec> specs(MenuItemRequest request) {
        return request.optionGroups() == null ? List.of()
                : request.optionGroups().stream().map(OptionGroupRequest::toSpec).toList();
    }

    public MenuItemResponse changeAvailability(Long id, boolean available) {
        MenuItem item = find(id);
        item.changeAvailability(available);
        events.publishEvent(new MenuAvailabilityChangedEvent(List.of(item.getId())));
        return MenuItemResponse.from(item);
    }

    /**
     * Sells dishes out, or back in, as the stock ledger finds their ingredients run out or restocked.
     *
     * @return the names of the dishes whose state changed
     */
    public List<String> markStockOut(Map<Long, Boolean> outByItem) {
        List<MenuItem> changed = repository.findAllById(outByItem.keySet()).stream()
                .filter(item -> item.markStockOut(outByItem.get(item.getId())))
                .toList();
        if (!changed.isEmpty()) {
            events.publishEvent(new MenuAvailabilityChangedEvent(changed.stream().map(MenuItem::getId).toList()));
        }
        return changed.stream().map(MenuItem::getName).sorted().toList();
    }

    public void archive(Long id) {
        find(id).archive();
    }

    private MenuItem find(Long id) {
        return repository.findById(id)
                .filter(item -> !item.isArchived())
                .orElseThrow(() -> new ResourceNotFoundException("menu_item", id));
    }

    private static boolean matches(MenuItem item, String needle) {
        return item.getName().toLowerCase(Locale.ROOT).contains(needle)
                || (item.getDescription() != null && item.getDescription().toLowerCase(Locale.ROOT).contains(needle));
    }

    private static BusinessRuleException nameTaken(String name) {
        return new BusinessRuleException("MENU_ITEM_NAME_TAKEN",
                "A menu item named '%s' already exists".formatted(name.strip()), Map.of("name", name.strip()));
    }
}
