package com.umutvarol.restaurant.inventory;

import com.umutvarol.restaurant.inventory.dto.IngredientRequest;
import com.umutvarol.restaurant.inventory.dto.IngredientResponse;
import com.umutvarol.restaurant.inventory.dto.MovementRequest;
import com.umutvarol.restaurant.inventory.dto.MovementResponse;
import com.umutvarol.restaurant.inventory.dto.StockReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Stock", description = "Ingredients and the stock ledger (managers)")
@RestController
@RequestMapping("/api/v1/stock")
class StockController {

    private final StockService stock;
    private final Clock clock;

    StockController(StockService stock, Clock clock) {
        this.stock = stock;
        this.clock = clock;
    }

    @Operation(summary = "Ingredients with what is on hand")
    @GetMapping("/ingredients")
    List<IngredientResponse> ingredients() {
        return stock.ingredients();
    }

    @Operation(summary = "Add an ingredient", description = "cost is per kilogram, litre or piece; lowStockLevel is in grams, millilitres or pieces.")
    @PostMapping("/ingredients")
    @ResponseStatus(HttpStatus.CREATED)
    IngredientResponse create(@Valid @RequestBody IngredientRequest request) {
        return stock.create(request);
    }

    @Operation(summary = "Change an ingredient")
    @PutMapping("/ingredients/{id}")
    IngredientResponse update(@PathVariable Long id, @Valid @RequestBody IngredientRequest request) {
        return stock.update(id, request);
    }

    @Operation(summary = "Remove an ingredient no recipe uses")
    @DeleteMapping("/ingredients/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable Long id) {
        stock.archive(id);
    }

    @Operation(summary = "Record a delivery, waste or count",
            description = "Appends to the ledger; nothing is ever edited. A count records the difference to what the ledger says.")
    @PostMapping("/ingredients/{id}/movements")
    @ResponseStatus(HttpStatus.CREATED)
    MovementResponse record(@PathVariable Long id, @Valid @RequestBody MovementRequest request, Authentication authentication) {
        return stock.record(id, request, authentication.getName());
    }

    @Operation(summary = "An ingredient's latest movements")
    @GetMapping("/ingredients/{id}/movements")
    List<MovementResponse> movements(@PathVariable Long id, @RequestParam(defaultValue = "50") int limit) {
        return stock.movements(id, limit);
    }

    @Operation(summary = "Theoretical against actual use", description = "Both days included; defaults to the last seven days.")
    @GetMapping("/report")
    StockReportResponse report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate last = to != null ? to : LocalDate.now(clock);
        return stock.report(from != null ? from : last.minusDays(6), last);
    }
}
