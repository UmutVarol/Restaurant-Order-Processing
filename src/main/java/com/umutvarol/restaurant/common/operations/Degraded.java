package com.umutvarol.restaurant.common.operations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.health.contributor.Status;

/**
 * Health for work that fails without anyone seeing it: a legal document not issued, a ticket not printed, a guest's
 * payment left unfinished. Such a backlog is {@link #STATUS DEGRADED}: managers see it in {@code /actuator/health}
 * and the monitoring system alerts on it, but the readiness probe stays up, because the till must keep working
 * while someone looks (see docs/OPERATIONS.md).
 */
public final class Degraded {

    /** Worse than UP, better than DOWN; answered with HTTP 200 (management.endpoint.health.status). */
    public static final Status STATUS = new Status("DEGRADED");

    private Degraded() {
    }

    /** How long ago the oldest waiting item was created, or zero when nothing waits. */
    public static Duration age(Optional<Instant> oldest, Clock clock) {
        return oldest.map(at -> Duration.between(at, clock.instant())).filter(age -> !age.isNegative()).orElse(Duration.ZERO);
    }
}
