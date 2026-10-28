package com.umutvarol.restaurant.printing;

import com.umutvarol.restaurant.printing.dto.PrintJobResponse;
import com.umutvarol.restaurant.printing.dto.PrinterRequest;
import com.umutvarol.restaurant.printing.dto.PrinterResponse;
import com.umutvarol.restaurant.printing.dto.TicketPreview;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Printers", description = "Kitchen and bar ticket printers")
@RestController
class PrinterController {

    private final PrintingService printing;

    PrinterController(PrintingService printing) {
        this.printing = printing;
    }

    @Operation(summary = "Printers")
    @GetMapping("/api/v1/printers")
    List<PrinterResponse> printers() {
        return printing.printers();
    }

    @Operation(summary = "Add a printer", description = "Only private-network addresses and the configured port range.")
    @PostMapping("/api/v1/printers")
    @ResponseStatus(HttpStatus.CREATED)
    PrinterResponse create(@Valid @RequestBody PrinterRequest request) {
        return printing.save(null, request);
    }

    @Operation(summary = "Change a printer")
    @PutMapping("/api/v1/printers/{id}")
    PrinterResponse update(@PathVariable Long id, @Valid @RequestBody PrinterRequest request) {
        return printing.save(id, request);
    }

    @Operation(summary = "Remove a printer")
    @DeleteMapping("/api/v1/printers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable Long id) {
        printing.delete(id);
    }

    @Operation(summary = "Print a test page")
    @PostMapping("/api/v1/printers/{id}/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    PrintJobResponse test(@PathVariable Long id, Authentication authentication) {
        return printing.test(id, authentication.getName());
    }

    @Operation(summary = "Preview a ticket", description = "An order's ticket for this printer, or the test page.")
    @GetMapping("/api/v1/printers/{id}/preview")
    TicketPreview preview(@PathVariable Long id, @RequestParam(required = false) Long orderId) {
        return printing.preview(id, orderId);
    }

    @Operation(summary = "Recent print jobs")
    @GetMapping("/api/v1/print-jobs")
    List<PrintJobResponse> jobs() {
        return printing.recentJobs();
    }

    @Operation(summary = "Print an order's tickets again")
    @PostMapping("/api/v1/orders/{id}/reprint")
    @ResponseStatus(HttpStatus.ACCEPTED)
    List<PrintJobResponse> reprint(@PathVariable Long id, Authentication authentication) {
        return printing.reprint(id, authentication.getName());
    }
}
