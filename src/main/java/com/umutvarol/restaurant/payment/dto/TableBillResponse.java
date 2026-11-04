package com.umutvarol.restaurant.payment.dto;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * What a table owes, as its QR page shows it before the guest opens the bill.
 *
 * @param reason why the guests cannot pay from their phones, or {@code null} when they can: {@code DISABLED} when
 *               online payments are off, {@code NOTHING_DUE} when the table has no open bill with anything left on it
 */
public record TableBillResponse(boolean payable, @Nullable String reason, BigDecimal total, BigDecimal due) {
}
