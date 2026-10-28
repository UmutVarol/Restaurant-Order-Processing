package com.umutvarol.restaurant.menu;

import com.umutvarol.restaurant.menu.dto.StationRequest;
import com.umutvarol.restaurant.menu.dto.StationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Stations", description = "Where in the kitchen dishes are made")
@RestController
@RequestMapping("/api/v1/stations")
class StationController {

    private final StationService stations;

    StationController(StationService stations) {
        this.stations = stations;
    }

    @Operation(summary = "Kitchen stations")
    @GetMapping
    List<StationResponse> list() {
        return stations.list();
    }

    @Operation(summary = "Add a station")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    StationResponse create(@Valid @RequestBody StationRequest request) {
        return stations.create(request);
    }

    @Operation(summary = "Rename a station or change its time target")
    @PutMapping("/{id}")
    StationResponse update(@PathVariable Long id, @Valid @RequestBody StationRequest request) {
        return stations.update(id, request);
    }

    @Operation(summary = "Remove a station no dish uses")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable Long id) {
        stations.delete(id);
    }
}
