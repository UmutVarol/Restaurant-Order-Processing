package com.umutvarol.restaurant.floor;

import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.table.TableZone;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A table together with the orders currently open on it, the party seated there and its next booking.
 * {@code attentionSince}: since when the guests at an occupied table have been without anyone seeing to them: their
 * oldest open call, bill request or cart, else the last time an order of theirs changed (taken, cooked, served).
 */
public record TableStatusResponse(
        Long id,
        String code,
        int seats,
        TableZone zone,
        boolean occupied,
        Instant occupiedSince,
        BigDecimal openTotal,
        List<OpenOrder> openOrders,
        Instant qrIssuedAt,
        Instant needsClearingSince,
        Party seatedParty,
        Party nextBooking,
        Instant attentionSince) {

    public record OpenOrder(Long id, OrderStatus status, String customerName, int itemCount, BigDecimal total,
                            Instant createdAt) {
    }

    /** A party from the reservations: seated here (since {@code at}) or booked for {@code at}. */
    public record Party(Long reservationId, String guestName, int partySize, Instant at) {
    }
}
