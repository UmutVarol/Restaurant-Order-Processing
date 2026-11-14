package com.umutvarol.restaurant.business;

import static org.assertj.core.api.Assertions.assertThat;

import com.umutvarol.restaurant.business.Business.Plan;
import com.umutvarol.restaurant.business.Business.Status;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** An unpaid business is never deleted: it turns read-only, and paying opens it again. */
class BusinessSubscriptionTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
    private static final Duration GRACE = Duration.ofDays(14);

    @Test
    void aTrialWorksUntilItEndsAndThenOnlyReads() {
        Business trial = new Business("kafe", "Kafe", "Europe/Istanbul", Plan.BUSINESS, Status.TRIAL, NOW.plus(Duration.ofDays(1)), NOW);
        assertThat(trial.writable(NOW)).isTrue();
        assertThat(trial.lapse(NOW, GRACE)).isFalse();
        // even before the hourly job runs, an ended trial stops writing
        assertThat(trial.writable(NOW.plus(Duration.ofDays(2)))).isFalse();
        assertThat(trial.lapse(NOW.plus(Duration.ofDays(2)), GRACE)).isTrue();
        assertThat(trial.getStatus()).isEqualTo(Status.READ_ONLY);
    }

    @Test
    void aLatePaymentHasAGracePeriodAndPayingClearsIt() {
        Business business = new Business("kafe", "Kafe", "Europe/Istanbul", Plan.BUSINESS, Status.ACTIVE, null, NOW);
        business.subscribed("PADDLE", "ctm", "sub", Plan.CHAIN, BillingInterval.MONTHLY, 3, Status.PAST_DUE, null, null, NOW);
        assertThat(business.getStatus()).isEqualTo(Status.PAST_DUE);
        assertThat(business.writable(NOW)).isTrue();
        // a second "still late" does not restart the grace period
        business.subscribed("PADDLE", "ctm", "sub", Plan.CHAIN, BillingInterval.MONTHLY, 3, Status.PAST_DUE, null, null, NOW.plus(Duration.ofDays(10)));
        assertThat(business.getPastDueSince()).isEqualTo(NOW);
        assertThat(business.lapse(NOW.plus(Duration.ofDays(13)), GRACE)).isFalse();
        assertThat(business.lapse(NOW.plus(Duration.ofDays(14)), GRACE)).isTrue();
        assertThat(business.writable(NOW.plus(Duration.ofDays(14)))).isFalse();

        business.subscribed("PADDLE", "ctm", "sub", Plan.CHAIN, BillingInterval.MONTHLY, 3, Status.ACTIVE, null, null, NOW.plus(Duration.ofDays(15)));
        assertThat(business.getStatus()).isEqualTo(Status.ACTIVE);
        assertThat(business.getPastDueSince()).isNull();
        assertThat(business.getPlan()).isEqualTo(Plan.CHAIN);
    }

    @Test
    void anOlderEventArrivingLateChangesNothing() {
        Business business = new Business("kafe", "Kafe", "Europe/Istanbul", Plan.BUSINESS, Status.ACTIVE, null, NOW);
        assertThat(business.subscribed("PADDLE", "ctm", "sub", Plan.CHAIN, BillingInterval.MONTHLY, 3, Status.CANCELLED,
                null, NOW, NOW)).isTrue();
        assertThat(business.subscribed("PADDLE", "ctm", "sub", Plan.CHAIN, BillingInterval.MONTHLY, 3, Status.ACTIVE,
                null, NOW.minusSeconds(60), NOW)).isFalse();
        assertThat(business.getStatus()).isEqualTo(Status.CANCELLED);
    }
}
