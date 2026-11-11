package com.umutvarol.restaurant.reservation.dto;

import com.umutvarol.restaurant.reservation.ReservationKind;
import com.umutvarol.restaurant.reservation.ReservationStatus;
import java.time.Instant;

public record ReservationResponse(
        Long id,
        ReservationKind kind,
        ReservationStatus status,
        String guestName,
        String phone,
        int partySize,
        Instant startsAt,
        Instant endsAt,
        int durationMinutes,
        Integer quotedMinutes,
        Long tableId,
        String tableCode,
        String note,
        Instant createdAt,
        String createdBy,
        Instant seatedAt,
        Instant closedAt) {
}
