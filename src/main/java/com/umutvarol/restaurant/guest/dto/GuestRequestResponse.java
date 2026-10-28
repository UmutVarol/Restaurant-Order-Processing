package com.umutvarol.restaurant.guest.dto;

import com.umutvarol.restaurant.guest.GuestRequest;
import com.umutvarol.restaurant.guest.GuestRequestKind;
import com.umutvarol.restaurant.guest.GuestRequestStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** A request in the staff inbox. */
public record GuestRequestResponse(Long id, GuestRequestKind kind, GuestRequestStatus status, Long tableId,
                                   String tableCode, @Nullable String guestName, @Nullable String note,
                                   @Nullable BigDecimal quotedTotal, @Nullable Long orderId, @Nullable String reason,
                                   Instant createdAt, @Nullable Instant handledAt, @Nullable String handledBy,
                                   List<Line> lines) {

    public record Line(String name, @Nullable String icon, int quantity, @Nullable String options, @Nullable String note,
                       BigDecimal lineTotal) {
    }

    public static GuestRequestResponse from(GuestRequest request) {
        return new GuestRequestResponse(request.getId(), request.getKind(), request.getStatus(),
                request.getTable().getId(), request.getTable().getCode(), request.getGuestName(), request.getNote(),
                request.getQuotedTotal(), request.getOrderId(), request.getReason(), request.getCreatedAt(),
                request.getHandledAt(), request.getHandledBy(),
                request.getLines().stream().map(line -> new Line(line.getItemName(), line.getItemIcon(),
                        line.getQuantity(), line.getOptionsText(), line.getNote(), line.getLineTotal())).toList());
    }
}
