package com.umutvarol.restaurant.order.pricing;

import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The VAT within several orders by rate: a bill's receipt, invoice or e-Adisyon lists one line per rate. */
public final class Vat {

    private static final BigDecimal LEGACY_RATE = BigDecimal.TEN.setScale(2);

    private Vat() {
    }

    /**
     * Adds up the orders' VAT by rate, cancelled orders left out. An order placed before prices included VAT has no
     * lines by rate: its tax, added on top at the deployment's single rate then, counts at 10 %.
     */
    public static List<VatLine> of(Collection<Order> orders) {
        Map<BigDecimal, BigDecimal[]> byRate = new TreeMap<>();
        for (Order order : orders) {
            if (order.getStatus() == OrderStatus.CANCELLED) {
                continue;
            }
            PriceBreakdown pricing = order.getPricing();
            List<VatLine> lines = pricing.taxIncluded() ? pricing.vat()
                    : List.of(new VatLine(LEGACY_RATE, pricing.total(), pricing.tax()));
            for (VatLine line : lines) {
                BigDecimal[] sum = byRate.computeIfAbsent(line.rate(), rate -> new BigDecimal[] {Money.ZERO, Money.ZERO});
                sum[0] = sum[0].add(line.gross());
                sum[1] = sum[1].add(line.vat());
            }
        }
        List<VatLine> result = new ArrayList<>();
        byRate.forEach((rate, sum) -> result.add(new VatLine(rate, sum[0], sum[1])));
        return result;
    }
}
