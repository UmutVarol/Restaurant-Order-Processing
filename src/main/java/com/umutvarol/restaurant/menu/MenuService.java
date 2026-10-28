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

    public MenuService(MenuItemRepository repository, StationService stations) {
        this.repository = repository;
        this.stations = stations;
    }

    @Transactional(readOnly = true)
    public List<MenuItemResponse> list(MenuCategory category, String query, boolean includeUnavailable) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return repository.findByArchivedFalse().stream()
                .filter(item -> category == null || item.getCategory() == category)
                .filter(item -> includeUnavailable || item.isOrderable())
                .filter(item -> needle.isEmpty() || matches(item, needle))
                .sorted(MENU_ORDER)
                .map(MenuItemResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public MenuItemResponse get(Long id) {
        return MenuItemResponse.from(find(id));
    }

    public MenuItemResponse create(MenuItemRequest request) {
        if (repository.existsByNameIgnoreCase(request.name().strip())) {
            throw nameTaken(request.name());
        }
        MenuItem item = new MenuItem(request.name(), request.description(), request.category(), request.price(),
                request.icon(), request.prepTimeMinutesOrDefault(), request.popularOrDefault(), request.tags());
        item.replaceOptions(specs(request));
        // Stored explicitly, so the station counts as in use and cannot be removed from under the dish.
        assignStation(item, request.stationCode() != null ? request.stationCode() : Station.defaultCode(item.getCategory()));
        return MenuItemResponse.from(repository.saveAndFlush(item));
    }

    public MenuItemResponse update(Long id, MenuItemRequest request) {
        MenuItem item = find(id);
        if (repository.existsByNameIgnoreCaseAndIdNot(request.name().strip(), id)) {
            throw nameTaken(request.name());
        }
        item.update(request.name(), request.description(), request.category(), request.price(), request.icon(),
                request.prepTimeMinutesOrDefault(), request.popularOrDefault(), request.tags());
        if (request.stationCode() != null) {
            assignStation(item, request.stationCode());
        }
        // Left out of the request, the options stay as they are; an empty list removes them.
        if (request.optionGroups() != null) {
            item.replaceOptions(specs(request));
            repository.flush();
        }
        return MenuItemResponse.from(item);
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
        return MenuItemResponse.from(item);
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
