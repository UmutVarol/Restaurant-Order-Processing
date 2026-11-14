package com.umutvarol.restaurant.printing;

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
 * Kitchen and bar tickets. A ticket that never printed is a dish nobody cooks: a job that failed in the last hour,
 * or one queued longer than {@code restaurant.operations.print-queue-tolerated}, degrades health.
 * <ul>
 *     <li>{@code sofra.print.failed}: jobs that failed in the last hour</li>
 *     <li>{@code sofra.print.queued.oldest}: seconds the oldest queued job has waited</li>
 * </ul>
 * Exposed as the health component {@code printing}.
 */
@Component("printingHealthIndicator")
class PrintingOperations implements HealthIndicator {

    private final PrintJobRepository jobs;
    private final Clock clock;
    private final Duration tolerated;

    PrintingOperations(PrintJobRepository jobs, Clock clock, MeterRegistry registry,
                       @Value("${restaurant.operations.print-queue-tolerated:PT2M}") Duration tolerated) {
        this.jobs = jobs;
        this.clock = clock;
        this.tolerated = tolerated;
        Gauge.builder("sofra.print.failed", this, PrintingOperations::failedLastHour)
                .description("Print jobs that failed in the last hour").register(registry);
        Gauge.builder("sofra.print.queued.oldest", this, operations -> operations.oldestQueued().toSeconds())
                .description("How long the oldest queued print job has waited").baseUnit("seconds").register(registry);
    }

    private long failedLastHour() {
        // monitoring watches the whole installation, every business at once
        return com.umutvarol.restaurant.business.BusinessContext.acrossAll(() -> jobs.countByStatusAndCreatedAtAfter(PrintJob.Status.FAILED, clock.instant().minus(Duration.ofHours(1))));
    }

    private Duration oldestQueued() {
        // monitoring watches the whole installation, every business at once
        return com.umutvarol.restaurant.business.BusinessContext.acrossAll(() -> Degraded.age(jobs.findFirstByStatusOrderByCreatedAtAsc(PrintJob.Status.QUEUED).map(PrintJob::getCreatedAt), clock));
    }

    @Override
    public Health health() {
        long failed = failedLastHour();
        Duration queued = oldestQueued();
        return Health.status(failed > 0 || queued.compareTo(tolerated) > 0 ? Degraded.STATUS : Status.UP)
                .withDetail("failedLastHour", failed)
                .withDetail("oldestQueuedSeconds", queued.toSeconds())
                .build();
    }
}
