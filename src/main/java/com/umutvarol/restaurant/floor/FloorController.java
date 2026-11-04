package com.umutvarol.restaurant.floor;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Floor", description = "Tables and their live occupancy")
@RestController
@RequestMapping("/api/v1/floor")
class FloorController {

    private final FloorService floorService;
    private final TableClearingService clearing;

    FloorController(FloorService floorService, TableClearingService clearing) {
        this.floorService = floorService;
        this.clearing = clearing;
    }

    @Operation(summary = "Floor plan", description = "All tables with the dine-in orders currently open on them.")
    @GetMapping("/tables")
    List<TableStatusResponse> tables() {
        return floorService.floorPlan();
    }

    @Operation(summary = "Mark a table cleared", description = "The guests left and the table is laid again.")
    @PostMapping("/tables/{id}/cleared")
    TableStatusResponse cleared(@PathVariable Long id, Authentication authentication) {
        return clearing.cleared(id, authentication.getName(), floorService);
    }
}
