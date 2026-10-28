package com.umutvarol.restaurant.printing.dto;

import com.umutvarol.restaurant.printing.PrintJob;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record PrintJobResponse(Long id, Long printerId, String printer, @Nullable Long orderId, PrintJob.Kind kind,
                               PrintJob.Status status, int attempts, @Nullable String lastError, Instant createdAt,
                               @Nullable Instant printedAt) {

    public static PrintJobResponse from(PrintJob job) {
        return new PrintJobResponse(job.getId(), job.getPrinter().getId(), job.getPrinter().getName(), job.getOrderId(),
                job.getKind(), job.getStatus(), job.getAttempts(), job.getLastError(), job.getCreatedAt(), job.getPrintedAt());
    }
}
