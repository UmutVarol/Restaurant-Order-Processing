package com.umutvarol.restaurant.payment.dto;

import com.umutvarol.restaurant.order.PaymentMethod;
import com.umutvarol.restaurant.payment.Payment;
import com.umutvarol.restaurant.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PaymentResponse(
        Long id,
        Long billId,
        PaymentMethod method,
        PaymentStatus status,
        BigDecimal amount,
        BigDecimal tip,
        BigDecimal tendered,
        BigDecimal changeDue,
        BigDecimal refunded,
        BigDecimal refundable,
        String approvalCode,
        String provider,
        boolean viaLink,
        String failureReason,
        Instant createdAt,
        Instant completedAt,
        String createdBy,
        List<RefundResponse> refunds) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getBill().getId(),
                payment.getMethod(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getTip(),
                payment.getTendered(),
                payment.getChangeDue(),
                payment.getRefundedAmount(),
                payment.refundable(),
                payment.getApprovalCode(),
                payment.getProvider(),
                payment.getLink() != null,
                payment.getFailureReason(),
                payment.getCreatedAt(),
                payment.getCompletedAt(),
                payment.getCreatedBy(),
                payment.getRefunds().stream().map(RefundResponse::from).toList());
    }
}
