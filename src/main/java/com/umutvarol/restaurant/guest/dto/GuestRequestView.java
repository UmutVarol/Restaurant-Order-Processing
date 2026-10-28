package com.umutvarol.restaurant.guest.dto;

import com.umutvarol.restaurant.guest.GuestRequest;
import com.umutvarol.restaurant.guest.GuestRequestKind;
import com.umutvarol.restaurant.guest.GuestRequestStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** A request as its guest sees it: no staff names, no internal ids. */
public record GuestRequestView(String id, GuestRequestKind kind, GuestRequestStatus status, @Nullable BigDecimal total,
                               @Nullable String reason, Instant createdAt, List<Line> lines) {

    public record Line(String name, @Nullable String icon, int quantity, @Nullable String options, BigDecimal lineTotal) {
    }

    public static GuestRequestView from(GuestRequest request) {
        return new GuestRequestView(request.getPublicId(), request.getKind(), request.getStatus(),
                request.getQuotedTotal(), request.getReason(), request.getCreatedAt(),
                request.getLines().stream().map(line -> new Line(line.getItemName(), line.getItemIcon(),
                        line.getQuantity(), line.getOptionsText(), line.getLineTotal())).toList());
    }
}
