package com.umutvarol.restaurant.menu;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.menu.dto.StationRequest;
import com.umutvarol.restaurant.menu.dto.StationResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class StationService {

    private final StationRepository stations;
    private final MenuItemRepository menuItems;

    public StationService(StationRepository stations, MenuItemRepository menuItems) {
        this.stations = stations;
        this.menuItems = menuItems;
    }

    @Transactional(readOnly = true)
    public List<StationResponse> list() {
        return stations.findAllByOrderBySortOrderAscIdAsc().stream().map(StationResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Set<String> codes() {
        return stations.findAll().stream().map(Station::getCode).collect(Collectors.toSet());
    }

    /** @throws BusinessRuleException {@code STATION_NOT_FOUND} for a code that is not a station */
    @Transactional(readOnly = true)
    public void requireExisting(Set<String> codes) {
        Set<String> known = codes();
        codes.stream().filter(code -> !known.contains(code)).findFirst().ifPresent(code -> {
            throw new BusinessRuleException("STATION_NOT_FOUND", "There is no station " + code, Map.of("code", code));
        });
    }

    public StationResponse create(StationRequest request) {
        if (request.code() == null) {
            throw new BusinessRuleException("STATION_CODE_REQUIRED", "A new station needs a code");
        }
        if (stations.existsByCode(request.code())) {
            throw new BusinessRuleException("STATION_CODE_TAKEN", "Station code %s is taken".formatted(request.code()),
                    Map.of("code", request.code()));
        }
        requireUniqueName(request.name(), -1L);
        int order = stations.findAll().stream().mapToInt(Station::getSortOrder).max().orElse(0) + 1;
        return StationResponse.from(stations.save(new Station(request.code(), request.name().strip(), request.targetMinutes(), order)));
    }

    public StationResponse update(Long id, StationRequest request) {
        Station station = find(id);
        requireUniqueName(request.name(), id);
        station.update(request.name().strip(), request.targetMinutes());
        return StationResponse.from(station);
    }

    /** Only a station no dish uses can go; printers that listed it simply stop getting it. */
    public void delete(Long id) {
        Station station = find(id);
        if (menuItems.existsByStationCodeAndArchivedFalse(station.getCode())) {
            throw new InvalidStateException("STATION_IN_USE", "Dishes are still made at %s".formatted(station.getName()),
                    Map.of("name", station.getName()));
        }
        stations.delete(station);
    }

    private void requireUniqueName(String name, Long id) {
        if (stations.existsByNameIgnoreCaseAndIdNot(name.strip(), id)) {
            throw new BusinessRuleException("STATION_NAME_TAKEN", "A station called %s exists".formatted(name),
                    Map.of("name", name.strip()));
        }
    }

    private Station find(Long id) {
        return stations.findById(id).orElseThrow(() -> new ResourceNotFoundException("station", id));
    }
}
