package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.common.operations.Degraded;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Online payments still unfinished. They are checked with the provider after two minutes and given up after
 * {@code restaurant.payments.pending-ttl}; one older than {@code restaurant.operations.payment-tolerated} means the
 * reconciliation is not running or the provider is not answering, and degrades health.
 * <ul>
 *     <li>{@code sofra.payments.pending}: online payments unfinished</li>
 *     <li>{@code sofra.payments.pending.oldest}: seconds the oldest of them has waited</li>
 * </ul>
 * Exposed as the health component {@code onlinePayments}.
 */
@Component("onlinePaymentsHealthIndicator")
class PaymentOperations implements HealthIndicator {

    private final PaymentRepository payments;
    private final Clock clock;
    private final Duration tolerated;

    PaymentOperations(PaymentRepository payments, Clock clock, MeterRegistry registry,
                      @Value("${restaurant.operations.payment-tolerated:PT40M}") Duration tolerated) {
        this.payments = payments;
        this.clock = clock;
        this.tolerated = tolerated;
        Gauge.builder("sofra.payments.pending", this, PaymentOperations::pending)
                .description("Online payments not finished yet").register(registry);
        Gauge.builder("sofra.payments.pending.oldest", this, operations -> operations.oldest().toSeconds())
                .description("How long the oldest unfinished online payment has waited").baseUnit("seconds").register(registry);
    }

    private long pending() {
        // monitoring watches the whole installation, every business at once
        return com.umutvarol.restaurant.business.BusinessContext.acrossAll(() -> payments.countByStatus(PaymentStatus.PENDING));
    }

    private Duration oldest() {
        // monitoring watches the whole installation, every business at once
        return com.umutvarol.restaurant.business.BusinessContext.acrossAll(() -> Degraded.age(payments.findFirstByStatusOrderByCreatedAtAsc(PaymentStatus.PENDING).map(Payment::getCreatedAt), clock));
    }

    @Override
    public Health health() {
        Duration oldest = oldest();
        return Health.status(oldest.compareTo(tolerated) > 0 ? Degraded.STATUS : Status.UP)
                .withDetail("pending", pending())
                .withDetail("oldestMinutes", oldest.toMinutes())
                .build();
    }
}
