package com.umutvarol.restaurant.order.pricing;

import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.coupon.Coupon;
import com.umutvarol.restaurant.order.OrderType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for order pricing. Both the live quote shown at the till and the order that is
 * finally persisted are priced here, so the guest is always charged exactly what they were shown.
 *
 * <p>Menu prices include VAT (KDV): the guest pays the lines, less any discount, plus the delivery fee, and the VAT
 * within that is worked out per rate. A discount is shared out between the rates in proportion to what each one
 * charges, so a discount lowers the VAT of every rate it touches.
 */
@Component
public class PricingCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PricingProperties properties;

    public PricingCalculator(PricingProperties properties) {
        this.properties = properties;
    }

    public PriceBreakdown calculate(List<TaxedAmount> lines, OrderType type, @Nullable Coupon coupon) {
        return calculate(lines, type, coupon, null);
    }

    /** As {@link #calculate(List, OrderType, Coupon)}, with a manager's discount on top of the coupon. */
    public PriceBreakdown calculate(List<TaxedAmount> lines, OrderType type, @Nullable Coupon coupon,
                                    @Nullable ManualDiscount manual) {
        BigDecimal subtotal = Money.normalize(lines.stream().map(TaxedAmount::gross).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal discount = discount(subtotal, coupon, manual);
        BigDecimal discountedSubtotal = subtotal.subtract(discount);
        BigDecimal deliveryFee = deliveryFee(discountedSubtotal, type);

        Map<BigDecimal, BigDecimal> byRate = shareOut(byRate(lines), discount);
        if (deliveryFee.signum() > 0) {
            byRate.merge(properties.deliveryVatPercent(), deliveryFee, BigDecimal::add);
        }
        List<VatLine> vat = new ArrayList<>();
        byRate.forEach((rate, gross) -> {
            if (gross.signum() > 0) {
                vat.add(new VatLine(rate, Money.normalize(gross), vatWithin(gross, rate)));
            }
        });
        BigDecimal tax = Money.normalize(vat.stream().map(VatLine::vat).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal total = Money.normalize(discountedSubtotal.add(deliveryFee));
        return new PriceBreakdown(subtotal, discount, tax, deliveryFee, total, VatLine.encode(vat), true);
    }

    /**
     * Reprices an order placed before menu prices included VAT, the way it was priced then: its lines are before
     * VAT and the deployment's rate is added on top. Only a change to such an order (a void, a discount) uses this.
     */
    public PriceBreakdown calculateBeforeTax(BigDecimal subtotal, OrderType type, @Nullable Coupon coupon,
                                             @Nullable ManualDiscount manual) {
        BigDecimal normalizedSubtotal = Money.normalize(subtotal);
        BigDecimal discount = discount(normalizedSubtotal, coupon, manual);
        BigDecimal discountedSubtotal = normalizedSubtotal.subtract(discount);
        BigDecimal tax = Money.percentOf(discountedSubtotal, properties.taxRate());
        BigDecimal deliveryFee = deliveryFee(discountedSubtotal, type);
        BigDecimal total = discountedSubtotal.add(tax).add(deliveryFee);
        return new PriceBreakdown(normalizedSubtotal, discount, tax, deliveryFee, Money.normalize(total), null, false);
    }

    /** The VAT within an amount that includes it: {@code gross × rate / (100 + rate)}. */
    public static BigDecimal vatWithin(BigDecimal gross, BigDecimal ratePercent) {
        if (ratePercent.signum() == 0) {
            return Money.ZERO;
        }
        return gross.multiply(ratePercent).divide(HUNDRED.add(ratePercent), Money.SCALE, Money.ROUNDING);
    }

    /** The amount before VAT within an amount that includes it. */
    public static BigDecimal netOf(BigDecimal gross, BigDecimal ratePercent) {
        return Money.normalize(gross).subtract(vatWithin(gross, ratePercent));
    }

    /** How much more the guest needs to order to get free delivery; zero when it already applies. */
    public BigDecimal amountUntilFreeDelivery(PriceBreakdown pricing, OrderType type) {
        if (type != OrderType.DELIVERY) {
            return Money.ZERO;
        }
        BigDecimal discountedSubtotal = pricing.subtotal().subtract(pricing.discount());
        return Money.normalize(properties.freeDeliveryThreshold().subtract(discountedSubtotal).max(BigDecimal.ZERO));
    }

    public BigDecimal freeDeliveryThreshold() {
        return Money.normalize(properties.freeDeliveryThreshold());
    }

    private static BigDecimal discount(BigDecimal subtotal, @Nullable Coupon coupon, @Nullable ManualDiscount manual) {
        BigDecimal couponDiscount = coupon == null ? Money.ZERO : coupon.discountFor(subtotal).min(subtotal);
        BigDecimal manualDiscount = manual == null ? Money.ZERO : manual.discountFor(subtotal.subtract(couponDiscount));
        return Money.normalize(couponDiscount.add(manualDiscount));
    }

    private static Map<BigDecimal, BigDecimal> byRate(List<TaxedAmount> lines) {
        Map<BigDecimal, BigDecimal> byRate = new TreeMap<>();
        for (TaxedAmount line : lines) {
            byRate.merge(line.rate().setScale(2, RoundingMode.HALF_UP), line.gross(), BigDecimal::add);
        }
        return byRate;
    }

    /**
     * Takes the discount off each rate in proportion to what it charges, to the kuruş: each rate's share is rounded
     * down and the kuruş left over go to the rates that lost the most to rounding, so the shares add up exactly.
     */
    private static Map<BigDecimal, BigDecimal> shareOut(Map<BigDecimal, BigDecimal> byRate, BigDecimal discount) {
        BigDecimal subtotal = byRate.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<BigDecimal, BigDecimal> after = new TreeMap<>();
        if (discount.signum() == 0 || subtotal.signum() == 0) {
            byRate.forEach((rate, gross) -> after.put(rate, Money.normalize(gross)));
            return after;
        }
        long discountKurus = discount.movePointRight(2).longValueExact();
        record Share(BigDecimal rate, BigDecimal gross, long kurus, BigDecimal remainder) {
        }
        List<Share> shares = new ArrayList<>();
        long given = 0;
        for (Map.Entry<BigDecimal, BigDecimal> entry : byRate.entrySet()) {
            BigDecimal exact = entry.getValue().multiply(BigDecimal.valueOf(discountKurus)).divide(subtotal, 10, RoundingMode.HALF_UP);
            long kurus = exact.setScale(0, RoundingMode.DOWN).longValueExact();
            shares.add(new Share(entry.getKey(), entry.getValue(), kurus, exact.subtract(BigDecimal.valueOf(kurus))));
            given += kurus;
        }
        List<Share> byRemainder = new ArrayList<>(shares);
        byRemainder.sort(Comparator.comparing(Share::remainder).reversed());
        Map<BigDecimal, Long> extra = new TreeMap<>();
        for (int i = 0; i < discountKurus - given; i++) {
            extra.merge(byRemainder.get(i % byRemainder.size()).rate(), 1L, Long::sum);
        }
        for (Share share : shares) {
            long kurus = share.kurus() + extra.getOrDefault(share.rate(), 0L);
            after.put(share.rate(), Money.normalize(share.gross().subtract(BigDecimal.valueOf(kurus).movePointLeft(2))));
        }
        return after;
    }

    private BigDecimal deliveryFee(BigDecimal discountedSubtotal, OrderType type) {
        boolean qualifiesForFreeDelivery = discountedSubtotal.compareTo(properties.freeDeliveryThreshold()) >= 0;
        return type == OrderType.DELIVERY && !qualifiesForFreeDelivery
                ? Money.normalize(properties.deliveryFee())
                : Money.ZERO;
    }
}
