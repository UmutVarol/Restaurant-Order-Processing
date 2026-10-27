package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.order.PaymentMethod;
import com.umutvarol.restaurant.payment.DaySummary.MethodTotal;
import com.umutvarol.restaurant.payment.dto.DayReportResponse;
import com.umutvarol.restaurant.payment.dto.DayReportResponse.Closing;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-of-day takings (the Z report) and the cash count that closes a day. Days follow the restaurant's time zone.
 * Fiscal receipts (ÖKC, e-Arşiv) are issued by the certified till, not here.
 */
@Service
@Transactional
public class DayReportService {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final DayClosingRepository closings;
    private final PaymentAuditLog audit;
    private final Clock clock;

    public DayReportService(PaymentRepository payments, RefundRepository refunds, DayClosingRepository closings,
                            PaymentAuditLog audit, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.closings = closings;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DayReportResponse report(LocalDate date) {
        return new DayReportResponse(summarise(date), closings.findByBusinessDate(date).map(Closing::from).orElse(null));
    }

    public DayReportResponse close(LocalDate date, BigDecimal countedCash, @Nullable String note, String actor) {
        if (date.isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleException("DAY_NOT_OVER", "A day can only be closed once it has started",
                    Map.of("date", date.toString()));
        }
        if (closings.findByBusinessDate(date).isPresent()) {
            throw new InvalidStateException("DAY_ALREADY_CLOSED", "%s has already been closed".formatted(date),
                    Map.of("date", date.toString()));
        }
        DaySummary summary = summarise(date);
        DayClosing closing = closings.save(new DayClosing(date, summary, Money.normalize(countedCash),
                note == null || note.isBlank() ? null : note.strip(), clock.instant(), actor));
        audit.dayClosed(date.toString(), summary.expectedCash(), closing.getCountedCash(), actor);
        return new DayReportResponse(summary, Closing.from(closing));
    }

    DaySummary summarise(LocalDate date) {
        Instant from = date.atStartOfDay(clock.getZone()).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        List<Payment> taken = payments.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(from, to)
                .stream().filter(payment -> payment.getStatus() == PaymentStatus.SUCCEEDED).toList();
        List<Refund> given = refunds.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to).stream()
                .filter(refund -> refund.getStatus() != RefundStatus.FAILED)
                .toList();

        List<MethodTotal> methods = new ArrayList<>();
        for (PaymentMethod method : PaymentMethod.values()) {
            List<Payment> ofMethod = taken.stream().filter(payment -> payment.getMethod() == method).toList();
            methods.add(new MethodTotal(method, ofMethod.size(),
                    sum(ofMethod.stream().map(Payment::getAmount).toList()),
                    sum(ofMethod.stream().map(Payment::getTip).toList()),
                    sum(given.stream().filter(refund -> refund.getPayment().getMethod() == method)
                            .map(Refund::getAmount).toList())));
        }
        BigDecimal sales = sum(methods.stream().map(MethodTotal::amount).toList());
        BigDecimal tips = sum(methods.stream().map(MethodTotal::tips).toList());
        BigDecimal refunded = sum(methods.stream().map(MethodTotal::refunds).toList());
        MethodTotal cash = methods.getFirst();
        BigDecimal expectedCash = cash.amount().add(cash.tips()).subtract(cash.refunds());
        return new DaySummary(date, methods, sales, tips, refunded, expectedCash, taken.size());
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(Money.ZERO, BigDecimal::add);
    }
}
