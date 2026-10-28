package com.umutvarol.restaurant.fiscal;

import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.fiscal.dto.FiscalDocumentResponse;
import com.umutvarol.restaurant.fiscal.dto.InvoiceRequest;
import com.umutvarol.restaurant.fiscal.dto.ZReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Fiscal documents", description = "ÖKC receipts and e-Arşiv invoices")
@RestController
@RequestMapping("/api/v1/fiscal")
class FiscalController {

    private final FiscalService fiscal;
    private final Clock clock;

    FiscalController(FiscalService fiscal, Clock clock) {
        this.fiscal = fiscal;
        this.clock = clock;
    }

    @Operation(summary = "A day's documents, with older ones not issued yet", description = "Guest tax numbers are masked.")
    @GetMapping("/documents")
    List<FiscalDocumentResponse> documents(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return fiscal.documents(date != null ? date : LocalDate.now(clock));
    }

    @Operation(summary = "The legal document of a bill")
    @GetMapping("/bills/{billId}")
    FiscalDocumentResponse forBill(@PathVariable Long billId) {
        return fiscal.forBill(billId).orElseThrow(() -> new ResourceNotFoundException("fiscal_document", billId));
    }

    @Operation(summary = "A guest asks for an e-Arşiv invoice", description = """
            VKN (10 digits) or TCKN (11 digits), check digits verified. Issued when the bill is paid; refused once the \\
            bill's receipt is issued, as a bill gets one legal document only.""")
    @PostMapping("/bills/{billId}/invoice")
    FiscalDocumentResponse requestInvoice(@PathVariable Long billId, @Valid @RequestBody InvoiceRequest request) {
        return fiscal.requestInvoice(billId, request);
    }

    @Operation(summary = "Try a document that was not issued again")
    @PostMapping("/documents/{id}/retry")
    FiscalDocumentResponse retry(@PathVariable Long id) {
        return fiscal.retry(id);
    }

    @Operation(summary = "Z report reconciliation", description = "Sofra's documents of the day against the ÖKC's own total.")
    @GetMapping("/z-report")
    ZReportResponse zReport(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return fiscal.zReport(date != null ? date : LocalDate.now(clock));
    }
}
