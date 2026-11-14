package com.umutvarol.restaurant.floor;

import com.umutvarol.restaurant.floor.dto.TableRequest;
import com.umutvarol.restaurant.floor.dto.TableResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Tables", description = "Setting up the dining room")
@RestController
@RequestMapping("/api/v1/tables")
class TableAdminController {

    private final TableAdminService tables;

    TableAdminController(TableAdminService tables) {
        this.tables = tables;
    }

    @Operation(summary = "Tables on the floor")
    @GetMapping
    List<TableResponse> list() {
        return tables.list();
    }

    @Operation(summary = "Add a table", description = "A removed table with the same number comes back.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TableResponse add(@Valid @RequestBody TableRequest request, Authentication authentication) {
        return tables.add(request, authentication.getName());
    }

    @Operation(summary = "Change a table", description = "Renumbering waits until no guests or bookings are on it.")
    @PutMapping("/{id}")
    TableResponse update(@PathVariable Long id, @Valid @RequestBody TableRequest request, Authentication authentication) {
        return tables.update(id, request, authentication.getName());
    }

    @Operation(summary = "Remove a table", description = "It keeps its history; refused while guests or a booking hold it.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable Long id, Authentication authentication) {
        tables.remove(id, authentication.getName());
    }
}
