package com.umutvarol.restaurant.printing.dto;

/** A ticket as plain text, laid out for the printer's paper width. */
public record TicketPreview(String printer, int columns, String text) {
}
