package com.umutvarol.restaurant.fiscal;

import com.umutvarol.restaurant.common.operations.Degraded;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Legal documents (ÖKC receipts, e-Arşiv invoices) still to be issued. A paid bill without its document is a tax
 * problem, so a document waiting longer than {@code restaurant.operations.fiscal-tolerated} degrades health.
 * <ul>
 *     <li>{@code sofra.fiscal.unissued}: documents pending or failed</li>
 *     <li>{@code sofra.fiscal.unissued.oldest}: seconds the oldest of them has waited</li>
 * </ul>
 * Exposed as the health component {@code fiscal}.
 */
@Component("fiscalHealthIndicator")
class FiscalOperations implements HealthIndicator {

    private static final List<FiscalDocument.Status> UNISSUED = List.of(FiscalDocument.Status.PENDING, FiscalDocument.Status.FAILED);

    private final FiscalDocumentRepository documents;
    private final Clock clock;
    private final Duration tolerated;

    FiscalOperations(FiscalDocumentRepository documents, Clock clock, MeterRegistry registry,
                     @Value("${restaurant.operations.fiscal-tolerated:PT15M}") Duration tolerated) {
        this.documents = documents;
        this.clock = clock;
        this.tolerated = tolerated;
        Gauge.builder("sofra.fiscal.unissued", this, FiscalOperations::unissued)
                .description("Legal documents not issued yet (pending or failed)").register(registry);
        Gauge.builder("sofra.fiscal.unissued.oldest", this, operations -> operations.oldest().toSeconds())
                .description("How long the oldest unissued legal document has waited").baseUnit("seconds").register(registry);
    }

    private long unissued() {
        // monitoring watches the whole installation, every business at once
        return com.umutvarol.restaurant.business.BusinessContext.acrossAll(() -> documents.countByStatusIn(UNISSUED));
    }

    private Duration oldest() {
        // monitoring watches the whole installation, every business at once
        return com.umutvarol.restaurant.business.BusinessContext.acrossAll(() -> Degraded.age(documents.findFirstByStatusInOrderByCreatedAtAsc(UNISSUED).map(FiscalDocument::getCreatedAt), clock));
    }

    @Override
    public Health health() {
        Duration oldest = oldest();
        return Health.status(oldest.compareTo(tolerated) > 0 ? Degraded.STATUS : Status.UP)
                .withDetail("unissued", unissued())
                .withDetail("oldestMinutes", oldest.toMinutes())
                .withDetail("toleratedMinutes", tolerated.toMinutes())
                .build();
    }
}
