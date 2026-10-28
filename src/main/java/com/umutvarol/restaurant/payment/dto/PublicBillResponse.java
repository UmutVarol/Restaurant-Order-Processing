package com.umutvarol.restaurant.payment.dto;

import com.umutvarol.restaurant.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a guest sees after scanning the QR code: the items and totals, nothing about staff or other guests.
 *
 * @param share       a fixed amount set by the waiter for this guest, if any
 * @param payable     whether the guest can pay now
 * @param reason      why not, when {@code payable} is false: {@code PAID}, {@code EXPIRED} or {@code DISABLED}
 * @param lastPayment how the guest's latest attempt through this link ended
 */
public record PublicBillResponse(
        String restaurant,
        String tableCode,
        List<Line> lines,
        BigDecimal total,
        BigDecimal paid,
        BigDecimal due,
        BigDecimal share,
        boolean payable,
        String reason,
        Instant expiresAt,
        String provider,
        LastPayment lastPayment) {

    public record Line(String name, String icon, @Nullable String options, int quantity, BigDecimal lineTotal) {
    }

    public record LastPayment(PaymentStatus status, BigDecimal amount, BigDecimal tip, String failureReason,
                              Instant at) {
    }
}
