package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.payment.dto.CloseDayRequest;
import com.umutvarol.restaurant.payment.dto.DayReportResponse;
import com.umutvarol.restaurant.payment.dto.PaymentResponse;
import com.umutvarol.restaurant.payment.dto.RefundRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Cashier", description = "Refunds and the end-of-day report. ADMIN only.")
@RestController
@RequestMapping("/api/v1/payments")
class PaymentController {

    private final PaymentService paymentService;
    private final DayReportService dayReportService;
    private final Clock clock;

    PaymentController(PaymentService paymentService, DayReportService dayReportService, Clock clock) {
        this.paymentService = paymentService;
        this.dayReportService = dayReportService;
        this.clock = clock;
    }

    @Operation(summary = "Refund a payment",
            description = "Cash and card refunds are recorded as given; online refunds are sent to the provider.")
    @PostMapping("/{id}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    PaymentResponse refund(@PathVariable Long id, @Valid @RequestBody RefundRequest request,
                           Authentication authentication) {
        return paymentService.refund(id, request, authentication.getName());
    }

    @Operation(summary = "Z report", description = "Takings per payment method, tips, refunds and expected cash. Defaults to today.")
    @GetMapping("/day")
    DayReportResponse day(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return dayReportService.report(date == null ? LocalDate.now(clock) : date);
    }

    @Operation(summary = "Close a day", description = "Records the counted cash and the difference to what was expected.")
    @PostMapping("/day/close")
    @ResponseStatus(HttpStatus.CREATED)
    DayReportResponse close(@Valid @RequestBody CloseDayRequest request, Authentication authentication) {
        return dayReportService.close(request.date(), request.countedCash(), request.note(), authentication.getName());
    }
}
