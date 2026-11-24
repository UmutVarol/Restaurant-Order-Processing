package com.umutvarol.restaurant.compliance;

import com.umutvarol.restaurant.order.pricing.VatLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The port to the business's GİB-authorised e-Adisyon integrator. Implementations must treat
 * {@code ettn + version} as an idempotency key: the same version sent twice is accepted once.
 */
interface AdisyonSender {

    String provider();

    /** @throws com.umutvarol.restaurant.common.error.UpstreamServiceException when it refuses or does not answer */
    Accepted send(Request request);

    /**
     * One version of an e-Adisyon. Prices include VAT.
     *
     * @param fiscalNumber the number of the receipt or invoice that closed it, once there is one
     */
    record Request(String ettn, int version, Adisyon.Status status, Long billId, @Nullable String tableCode,
                   Instant openedAt, @Nullable Instant closedAt, List<Line> lines, BigDecimal total, BigDecimal tax,
                   List<VatLine> vat, @Nullable String fiscalNumber) {
    }

    record Line(String name, int quantity, BigDecimal unitPrice, BigDecimal total, BigDecimal vatRate) {
    }

    record Accepted(String documentNumber) {
    }
}
