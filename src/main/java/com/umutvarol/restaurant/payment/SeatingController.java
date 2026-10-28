package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.payment.dto.BillResponse;
import com.umutvarol.restaurant.payment.dto.TableTransferRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Tables", description = "Moving and merging seatings")
@RestController
@RequestMapping("/api/v1/tables")
class SeatingController {

    private final SeatingService seating;

    SeatingController(SeatingService seating) {
        this.seating = seating;
    }

    @Operation(summary = "Move a table", description = "The seating, its orders and its bill move to a free table.")
    @PostMapping("/{id}/move")
    BillResponse move(@PathVariable long id, @Valid @RequestBody TableTransferRequest request,
                      Authentication authentication) {
        return seating.move(id, request.tableId(), authentication.getName());
    }

    @Operation(summary = "Merge tables", description = """
            Every order of this table joins the bill of the given table. Refused if this table's bill has already
            taken money.""")
    @PostMapping("/{id}/merge")
    BillResponse merge(@PathVariable long id, @Valid @RequestBody TableTransferRequest request,
                       Authentication authentication) {
        return seating.merge(id, request.tableId(), authentication.getName());
    }
}
