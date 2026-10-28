package com.umutvarol.restaurant.printing.dto;

import com.umutvarol.restaurant.printing.Printer;
import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public record PrinterResponse(Long id, String name, String host, int port, int paperWidth, Set<String> stations,
                              boolean enabled, @Nullable Instant lastPrintedAt, @Nullable String lastError) {

    public static PrinterResponse from(Printer printer) {
        return new PrinterResponse(printer.getId(), printer.getName(), printer.getHost(), printer.getPort(),
                printer.getPaperWidth(), printer.stations(), printer.isEnabled(), printer.getLastPrintedAt(),
                printer.getLastError());
    }
}
