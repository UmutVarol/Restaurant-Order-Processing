package com.umutvarol.restaurant.payment.dto;

import com.umutvarol.restaurant.payment.Refund;
import com.umutvarol.restaurant.payment.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record RefundResponse(Long id, BigDecimal amount, RefundStatus status, String reason, String failureReason,
                             Instant createdAt, String createdBy) {

    public static RefundResponse from(Refund refund) {
        return new RefundResponse(refund.getId(), refund.getAmount(), refund.getStatus(), refund.getReason(),
                refund.getFailureReason(), refund.getCreatedAt(), refund.getCreatedBy());
    }
}
