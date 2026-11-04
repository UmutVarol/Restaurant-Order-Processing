package com.umutvarol.restaurant.guest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Which carts from a table go straight to the kitchen, and why the others wait. */
class GuestOrderPolicyTest {

    private static final BigDecimal SMALL = new BigDecimal("250.00");
    private static final BigDecimal LARGE = new BigDecimal("2400.00");

    @Test
    void classicModeHoldsEveryCart() {
        ServiceSettings classic = settings(ServiceMode.CLASSIC, "1500.00", false);
        assertThat(GuestOrderPolicy.holdReason(classic, true, SMALL, false, 0)).contains(HoldReason.CLASSIC_MODE);
    }

    @Test
    void mixedModeHoldsTheFirstCartLargeCartsAndDishesThatNeedAWaiter() {
        ServiceSettings mixed = settings(ServiceMode.MIXED, "1500.00", true);
        assertThat(GuestOrderPolicy.holdReason(mixed, true, SMALL, false, 0)).isEmpty();
        assertThat(GuestOrderPolicy.holdReason(mixed, false, SMALL, false, 0)).contains(HoldReason.FIRST_CART);
        assertThat(GuestOrderPolicy.holdReason(mixed, true, LARGE, false, 0)).contains(HoldReason.OVER_LIMIT);
        assertThat(GuestOrderPolicy.holdReason(mixed, true, new BigDecimal("1500.00"), false, 0)).isEmpty();
        // a dish that always needs a waiter is the reason the guest is told, even at an empty table
        assertThat(GuestOrderPolicy.holdReason(mixed, false, LARGE, true, 0)).contains(HoldReason.NEEDS_APPROVAL);
    }

    @Test
    void mixedModeWithoutLimitOrFirstCartRuleLetsCartsThrough() {
        ServiceSettings open = settings(ServiceMode.MIXED, null, false);
        assertThat(GuestOrderPolicy.holdReason(open, false, LARGE, false, 0)).isEmpty();
    }

    @Test
    void qrFirstOnlyHoldsDishesThatNeedAWaiter() {
        ServiceSettings qrFirst = settings(ServiceMode.QR_FIRST, "100.00", true);
        assertThat(GuestOrderPolicy.holdReason(qrFirst, false, LARGE, false, 0)).isEmpty();
        assertThat(GuestOrderPolicy.holdReason(qrFirst, false, SMALL, true, 0)).contains(HoldReason.NEEDS_APPROVAL);
    }

    @Test
    void aTableThatJustSentSeveralCartsOnItsOwnWaitsWhateverTheMode() {
        int limit = GuestOrderPolicy.BURST_LIMIT;
        for (ServiceMode mode : new ServiceMode[] {ServiceMode.MIXED, ServiceMode.QR_FIRST}) {
            ServiceSettings settings = settings(mode, null, false);
            assertThat(GuestOrderPolicy.holdReason(settings, true, SMALL, false, limit - 1)).isEmpty();
            assertThat(GuestOrderPolicy.holdReason(settings, true, SMALL, false, limit)).contains(HoldReason.BURST);
        }
    }

    private static ServiceSettings settings(ServiceMode mode, String limit, boolean firstCartNeedsStaff) {
        ServiceSettings settings = new ServiceSettings();
        settings.change(mode, limit == null ? null : new BigDecimal(limit), firstCartNeedsStaff, Instant.EPOCH, "test");
        return settings;
    }
}
