package com.umutvarol.restaurant.guest.dto;

import java.time.Instant;

/** A new QR token for a table, returned once for printing; the server keeps only its hash. */
public record TableQrResponse(Long tableId, String tableCode, String token, Instant issuedAt) {

    @Override
    public String toString() {
        return "TableQrResponse[tableId=" + tableId + ", tableCode=" + tableCode + ", token=***]";
    }
}
