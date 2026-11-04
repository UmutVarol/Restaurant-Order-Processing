package com.umutvarol.restaurant.guest;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/**
 * Decides whether a cart sent from a table goes straight to the kitchen or waits for a waiter. Prices are the
 * server's own quote, never the guest's. Whatever the mode, a table that has just sent several carts on its own
 * waits: a photographed QR code cannot keep the kitchen busy.
 */
final class GuestOrderPolicy {

    /**
     * At most this many carts from one table go to the kitchen on their own within {@link #BURST_WINDOW}: enough for
     * a group ordering from several phones, too few to keep a kitchen busy from a photographed code.
     */
    static final int BURST_LIMIT = 6;
    static final Duration BURST_WINDOW = Duration.ofMinutes(10);

    private GuestOrderPolicy() {
    }

    /**
     * @param seated          whether the table already has orders in progress
     * @param needsApproval   whether a dish in the cart is one the restaurant always confirms
     * @param recentAutomatic carts from this table that went to the kitchen on their own within the burst window
     * @return why the cart waits, or empty when it can go to the kitchen now
     */
    static Optional<HoldReason> holdReason(ServiceSettings settings, boolean seated, BigDecimal total,
                                           boolean needsApproval, long recentAutomatic) {
        if (settings.getMode() == ServiceMode.CLASSIC) {
            return Optional.of(HoldReason.CLASSIC_MODE);
        }
        if (needsApproval) {
            return Optional.of(HoldReason.NEEDS_APPROVAL);
        }
        if (settings.getMode() == ServiceMode.MIXED) {
            if (settings.isFirstCartNeedsStaff() && !seated) {
                return Optional.of(HoldReason.FIRST_CART);
            }
            BigDecimal limit = settings.getAutoAcceptLimit();
            if (limit != null && total.compareTo(limit) > 0) {
                return Optional.of(HoldReason.OVER_LIMIT);
            }
        }
        if (recentAutomatic >= BURST_LIMIT) {
            return Optional.of(HoldReason.BURST);
        }
        return Optional.empty();
    }
}
