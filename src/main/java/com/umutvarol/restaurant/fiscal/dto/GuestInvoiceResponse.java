package com.umutvarol.restaurant.fiscal.dto;

import com.umutvarol.restaurant.fiscal.FiscalDocument;
import org.jspecify.annotations.Nullable;

/**
 * Whether a guest can ask for an invoice on the bill they are paying, and whose name it is in. The tax number is
 * never shown back.
 *
 * @param available whether e-Arşiv invoices are set up
 * @param name      the name the invoice is made out to, once asked for
 * @param status    the invoice's state, once asked for
 */
public record GuestInvoiceResponse(boolean available, @Nullable String name, FiscalDocument.@Nullable Status status) {
}
