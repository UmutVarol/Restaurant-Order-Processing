package com.umutvarol.restaurant.fiscal;

import com.umutvarol.restaurant.order.PaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The port to whatever issues legal documents: the yeni nesil ÖKC through its vendor's local bridge (GMP-3), or a
 * GİB-authorised e-Arşiv integrator. Implementations must treat {@link Request#key()} as an idempotency key: the
 * same key twice returns the document already issued.
 */
interface FiscalDevice {

    String provider();

    /** @throws com.umutvarol.restaurant.common.error.UpstreamServiceException when it refuses or does not answer */
    Issued issue(Request request);

    /** What the device itself recorded for a day (its Z report), when it can tell. */
    Optional<BigDecimal> dailyTotal(LocalDate day);

    record Request(String key, FiscalKind kind, Long billId, Instant at, List<Line> lines, BigDecimal total,
                   BigDecimal tax, Map<PaymentMethod, BigDecimal> payments, @Nullable Buyer buyer) {
    }

    record Line(String name, int quantity, BigDecimal unitPrice, BigDecimal total) {
    }

    record Buyer(String name, String taxId, @Nullable String address) {
    }

    record Issued(String reference, String number, @Nullable String pdfUrl) {
    }
}
