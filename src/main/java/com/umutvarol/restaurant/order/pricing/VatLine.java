package com.umutvarol.restaurant.order.pricing;

import com.umutvarol.restaurant.common.money.Money;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The VAT (KDV) within what the guest paid at one rate: {@code gross} is the amount charged at that rate, VAT
 * included, after discounts; {@code vat} is the part of it that is tax. Receipts, e-Arşiv invoices and e-Adisyon
 * documents list one of these per rate.
 *
 * @param rate  the rate as a percentage, e.g. {@code 10.00}
 * @param gross the amount at this rate, VAT included
 * @param vat   the VAT within {@code gross}
 */
public record VatLine(BigDecimal rate, BigDecimal gross, BigDecimal vat) {

    /** The amount before VAT. */
    public BigDecimal net() {
        return gross.subtract(vat);
    }

    /** Stored as "rate:gross:vat" joined by "|"; null for no lines. */
    public static @Nullable String encode(List<VatLine> lines) {
        if (lines.isEmpty()) {
            return null;
        }
        return lines.stream()
                .map(line -> line.rate.toPlainString() + ":" + line.gross.toPlainString() + ":" + line.vat.toPlainString())
                .collect(Collectors.joining("|"));
    }

    public static List<VatLine> decode(@Nullable String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split("\\|"))
                .map(part -> part.split(":"))
                .map(parts -> new VatLine(new BigDecimal(parts[0]).setScale(2), Money.normalize(new BigDecimal(parts[1])),
                        Money.normalize(new BigDecimal(parts[2]))))
                .toList();
    }
}
