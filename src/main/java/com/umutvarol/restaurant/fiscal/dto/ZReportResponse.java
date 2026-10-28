package com.umutvarol.restaurant.fiscal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A day's documents against the devices' own records.
 *
 * @param deviceReceiptTotal what the ÖKC's Z report says, when it can be read
 * @param difference         ours minus the device's; anything but zero needs looking into
 * @param waiting            documents not issued yet (pending or failed): sales not yet declared
 */
public record ZReportResponse(LocalDate date, int receipts, BigDecimal receiptTotal, int invoices, BigDecimal invoiceTotal,
                              BigDecimal deviceReceiptTotal, BigDecimal difference, int waiting) {
}
