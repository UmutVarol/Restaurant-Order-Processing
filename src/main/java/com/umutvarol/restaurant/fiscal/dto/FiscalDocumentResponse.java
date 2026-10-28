package com.umutvarol.restaurant.fiscal.dto;

import com.umutvarol.restaurant.fiscal.FiscalDocument;
import com.umutvarol.restaurant.fiscal.FiscalKind;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * @param buyerTaxId masked: the last three digits only
 * @param simulated  issued by a simulation, so nothing was declared to GİB
 */
public record FiscalDocumentResponse(Long id, Long billId, FiscalKind kind, FiscalDocument.Status status, String provider,
                                     String reference, String documentNumber, String pdfUrl, BigDecimal total, BigDecimal tax,
                                     String buyerName, String buyerTaxId, int attempts, String lastError,
                                     Instant nextAttemptAt, Instant createdAt, Instant issuedAt, boolean simulated) {
}
