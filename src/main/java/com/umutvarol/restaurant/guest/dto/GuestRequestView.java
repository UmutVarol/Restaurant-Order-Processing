package com.umutvarol.restaurant.guest.dto;

import com.umutvarol.restaurant.guest.GuestRequest;
import com.umutvarol.restaurant.guest.GuestRequestKind;
import com.umutvarol.restaurant.guest.GuestRequestStatus;
import com.umutvarol.restaurant.guest.HoldReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A request as its guest sees it: no staff names, no internal ids.
 *
 * @param waitingFor why an open cart waits for a waiter
 * @param automatic  whether the cart went to the kitchen by the service rules alone
 */
public record GuestRequestView(String id, GuestRequestKind kind, GuestRequestStatus status, @Nullable BigDecimal total,
                               @Nullable String reason, Instant createdAt, List<Line> lines,
                               @Nullable HoldReason waitingFor, boolean automatic) {

    public record Line(String name, @Nullable String icon, int quantity, @Nullable String options, BigDecimal lineTotal) {
    }

    public static GuestRequestView from(GuestRequest request) {
        return new GuestRequestView(request.getPublicId(), request.getKind(), request.getStatus(),
                request.getQuotedTotal(), request.getReason(), request.getCreatedAt(),
                request.getLines().stream().map(line -> new Line(line.getItemName(), line.getItemIcon(),
                        line.getQuantity(), line.getOptionsText(), line.getLineTotal())).toList(),
                request.getStatus() == GuestRequestStatus.OPEN ? request.getHoldReason() : null,
                request.isAutoAccepted());
    }
}
