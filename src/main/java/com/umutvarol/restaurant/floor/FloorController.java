package com.umutvarol.restaurant.floor;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Floor", description = "Tables and their live occupancy")
@RestController
@RequestMapping("/api/v1/floor")
class FloorController {

    private final FloorService floorService;

    FloorController(FloorService floorService) {
        this.floorService = floorService;
    }

    @Operation(summary = "Floor plan", description = "All tables with the dine-in orders currently open on them.")
    @GetMapping("/tables")
    List<TableStatusResponse> tables() {
        return floorService.floorPlan();
    }
}
