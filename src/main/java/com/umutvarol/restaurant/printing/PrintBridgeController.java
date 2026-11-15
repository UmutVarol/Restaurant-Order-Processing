package com.umutvarol.restaurant.printing;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Managers add and switch off a branch's print bridges. */
@Tag(name = "Printing", description = "Kitchen and bar printers")
@RestController
class PrintBridgeController {

    record BridgeRequest(@NotBlank @Size(max = 60) String name) {
    }

    private final PrintBridgeService bridges;

    PrintBridgeController(PrintBridgeService bridges) {
        this.bridges = bridges;
    }

    @Operation(summary = "Print bridges")
    @GetMapping("/api/v1/print-bridges")
    List<PrintBridgeService.BridgeView> list() {
        return bridges.list();
    }

    @Operation(summary = "Add a print bridge", description = "Its token is shown once, to set the bridge up with.")
    @PostMapping("/api/v1/print-bridges")
    ResponseEntity<PrintBridgeService.NewBridge> create(@Valid @RequestBody BridgeRequest request, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(bridges.create(request.name(), auth.getName()));
    }

    @Operation(summary = "Switch a print bridge off")
    @DeleteMapping("/api/v1/print-bridges/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable Long id, Authentication auth) {
        bridges.revoke(id, auth.getName());
    }
}
