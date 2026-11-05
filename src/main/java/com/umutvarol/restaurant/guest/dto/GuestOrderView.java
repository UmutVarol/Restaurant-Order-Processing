package com.umutvarol.restaurant.guest.dto;

import com.umutvarol.restaurant.order.OrderStatus;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An order at the table as its guests follow it on the table's QR page: what is on it and how far each dish has got.
 * No staff names, no prices, no guest names.
 *
 * @param readyBy when the kitchen expects the dishes it is making to be ready (by each station's target time), or
 *                {@code null} once nothing is being made
 */
public record GuestOrderView(Long number, OrderStatus status, Instant placedAt, Instant statusSince,
                             @Nullable Instant readyBy, List<Line> lines) {

    /** Where a dish is: held for a later course, waiting for the kitchen, being made, ready, or at the table. */
    public enum LineState { LATER, WAITING, COOKING, READY, SERVED }

    public record Line(String name, @Nullable String icon, int quantity, @Nullable String options, int course,
                       LineState state) {
    }
}
