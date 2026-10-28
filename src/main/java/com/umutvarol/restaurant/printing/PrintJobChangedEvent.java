package com.umutvarol.restaurant.printing;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A ticket printed or gave up, so tills can warn staff about a printer that is off or out of paper. */
public record PrintJobChangedEvent(Long jobId, Long printerId, String printer, @Nullable Long orderId,
                                   PrintJob.Status status, @Nullable String error, Instant occurredAt) {
}
