package com.umutvarol.restaurant.printing;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a print bridge calls: fetch the branch's tickets, report each one printed or failed. It signs in with its
 * token ({@code X-Bridge-Token}); it can only ever see and report its own branch's tickets.
 */
@Tag(name = "Printing", description = "Kitchen and bar printers")
@RestController
class BridgeApiController {

    record Claim(@Nullable Integer max) {
    }

    record Result(boolean ok, @Nullable @Size(max = 255) String error) {
    }

    private final PrintBridgeService bridges;
    private final PrintingService printing;

    BridgeApiController(PrintBridgeService bridges, PrintingService printing) {
        this.bridges = bridges;
        this.printing = printing;
    }

    @Operation(summary = "Tickets for this bridge", description = "Each one is held for the bridge for a minute.")
    @PostMapping("/api/v1/bridge/jobs/claim")
    List<PrintingService.BridgeJob> claim(@RequestHeader(value = BridgeBusinessLocator.HEADER, required = false) String token,
                                          @RequestBody(required = false) Claim claim) {
        PrintBridge bridge = bridges.authenticate(token);
        return printing.claimForBridge(bridge.getBranchId(), claim == null || claim.max() == null ? 5 : claim.max());
    }

    @Operation(summary = "Report a ticket printed or failed")
    @PostMapping("/api/v1/bridge/jobs/{id}/result")
    void result(@RequestHeader(value = BridgeBusinessLocator.HEADER, required = false) String token,
                @PathVariable long id, @Valid @RequestBody Result result) {
        PrintBridge bridge = bridges.authenticate(token);
        printing.bridgeResult(bridge.getBranchId(), id, result.ok() ? null : (result.error() == null ? "Bridge could not print" : result.error()));
    }
}
