package com.umutvarol.restaurant.fiscal;

import java.time.Instant;

/** A legal document was issued or failed, so the cashier screen shows it at once. */
public record FiscalDocumentChangedEvent(Long documentId, Long billId, FiscalDocument.Status status, Instant occurredAt) {
}
