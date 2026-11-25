package com.umutvarol.restaurant.planning;

import com.umutvarol.restaurant.business.Feature;
import com.umutvarol.restaurant.business.RequiresFeature;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Planning", description = "The day's likely sales, the prep list and what it needs from stock, and waste.")
@RequiresFeature(Feature.STOCK)
@RestController
@RequestMapping("/api/v1/planning")
class PlanningController {

    record PreparedRequest(boolean prepared) {
    }

    private final PlanningService planning;
    private final Clock clock;

    PlanningController(PlanningService planning, Clock clock) {
        this.planning = planning;
        this.clock = clock;
    }

    @Operation(summary = "A day's prep list", description = "Likely sales of each dish (from the same weekday of past "
            + "weeks), which are prepared, and what they need from stock. Today when no date is given.")
    @GetMapping("/prep")
    PlanningService.Prep prep(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate date) {
        return planning.prep(date == null ? LocalDate.now(clock) : date);
    }

    @Operation(summary = "Mark a dish prepared for a day, or not")
    @PutMapping("/prep/{date}/{menuItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void prepared(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, @PathVariable Long menuItemId,
                  @RequestBody PreparedRequest request, Authentication authentication) {
        planning.markPrepared(date, menuItemId, request.prepared(), authentication.getName());
    }

    @Operation(summary = "Record waste", description = "A dish's portions take its recipe's ingredients out of stock. "
            + "The same requestKey twice records it once.")
    @PostMapping("/waste")
    @ResponseStatus(HttpStatus.CREATED)
    PlanningService.WasteView waste(@RequestBody PlanningService.WasteRequest request, Authentication authentication) {
        return planning.recordWaste(request, authentication.getName());
    }

    @Operation(summary = "Today's waste")
    @GetMapping("/waste/today")
    List<PlanningService.WasteView> today() {
        return planning.today();
    }

    @Operation(summary = "Waste over a period", description = "Cost, against sales, by reason and by item; up to 92 days.")
    @GetMapping("/waste")
    PlanningService.WasteReport report(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return planning.wasteReport(from, to);
    }
}
