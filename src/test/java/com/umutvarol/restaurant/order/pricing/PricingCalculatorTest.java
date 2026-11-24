package com.umutvarol.restaurant.order.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.umutvarol.restaurant.coupon.Coupon;
import com.umutvarol.restaurant.coupon.DiscountType;
import com.umutvarol.restaurant.order.OrderType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PricingCalculatorTest {

    private final PricingCalculator calculator = new PricingCalculator(new PricingProperties(
            new BigDecimal("0.10"), new BigDecimal("0.10"), new BigDecimal("39.90"), new BigDecimal("750.00")));

    @Test
    void theMenuPriceIsWhatTheGuestPaysWithTheVatInsideIt() {
        PriceBreakdown price = calculator.calculate(food("550"), OrderType.TAKEAWAY, null);

        assertThat(price.subtotal()).isEqualTo(amount("550.00"));
        assertThat(price.tax()).isEqualTo(amount("50.00"));
        assertThat(price.total()).isEqualTo(amount("550.00"));
        assertThat(price.taxIncluded()).isTrue();
        assertThat(price.vat()).containsExactly(new VatLine(rate("10"), amount("550.00"), amount("50.00")));
    }

    @Test
    void eachRateCarriesItsOwnVat() {
        PriceBreakdown price = calculator.calculate(List.of(line("10", "330"), line("20", "240")), OrderType.DINE_IN, null);

        assertThat(price.total()).isEqualTo(amount("570.00"));
        assertThat(price.tax()).isEqualTo(amount("70.00"));
        assertThat(price.vat()).containsExactly(
                new VatLine(rate("10"), amount("330.00"), amount("30.00")),
                new VatLine(rate("20"), amount("240.00"), amount("40.00")));
    }

    @Test
    void aDiscountIsSharedBetweenTheRatesAndLowersTheirVat() {
        Coupon tenPercent = coupon(DiscountType.PERCENTAGE, "10", "0", null);

        PriceBreakdown price = calculator.calculate(List.of(line("10", "330"), line("20", "240")), OrderType.DINE_IN, tenPercent);

        assertThat(price.discount()).isEqualTo(amount("57.00"));
        assertThat(price.total()).isEqualTo(amount("513.00"));
        assertThat(price.vat()).containsExactly(
                new VatLine(rate("10"), amount("297.00"), amount("27.00")),
                new VatLine(rate("20"), amount("216.00"), amount("36.00")));
        assertThat(price.tax()).isEqualTo(amount("63.00"));
    }

    @Test
    void sharesOfADiscountAddUpToTheKurus() {
        Coupon one = coupon(DiscountType.FIXED_AMOUNT, "1", "0", null);

        PriceBreakdown price = calculator.calculate(List.of(line("1", "100"), line("10", "100"), line("20", "100")),
                OrderType.TAKEAWAY, one);

        assertThat(price.total()).isEqualTo(amount("299.00"));
        assertThat(price.vat().stream().map(VatLine::gross).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualTo(amount("299.00"));
    }

    @Test
    void roundsVatHalfUpToWholeKurus() {
        PriceBreakdown price = calculator.calculate(food("123.45"), OrderType.DINE_IN, null);

        assertThat(price.tax()).isEqualTo(amount("11.22"));
        assertThat(price.total()).isEqualTo(amount("123.45"));
    }

    @Test
    void zeroRatedLinesCarryNoVat() {
        PriceBreakdown price = calculator.calculate(List.of(line("0", "80")), OrderType.TAKEAWAY, null);

        assertThat(price.tax()).isEqualTo(amount("0.00"));
        assertThat(price.total()).isEqualTo(amount("80.00"));
    }

    @Test
    void chargesDeliveryFeeBelowTheFreeDeliveryThresholdWithItsVatInside() {
        PriceBreakdown price = calculator.calculate(food("500"), OrderType.DELIVERY, null);

        assertThat(price.deliveryFee()).isEqualTo(amount("39.90"));
        assertThat(price.total()).isEqualTo(amount("539.90"));
        assertThat(price.vat()).containsExactly(new VatLine(rate("10"), amount("539.90"), amount("49.08")));
        assertThat(calculator.amountUntilFreeDelivery(price, OrderType.DELIVERY)).isEqualTo(amount("250.00"));
    }

    @Test
    void deliversForFreeFromTheThresholdOn() {
        PriceBreakdown price = calculator.calculate(food("750"), OrderType.DELIVERY, null);

        assertThat(price.deliveryFee()).isEqualTo(amount("0.00"));
        assertThat(calculator.amountUntilFreeDelivery(price, OrderType.DELIVERY)).isEqualTo(amount("0.00"));
    }

    @Test
    void neverChargesDeliveryForOtherOrderTypes() {
        PriceBreakdown price = calculator.calculate(food("100"), OrderType.TAKEAWAY, null);

        assertThat(price.deliveryFee()).isEqualTo(amount("0.00"));
        assertThat(calculator.amountUntilFreeDelivery(price, OrderType.TAKEAWAY)).isEqualTo(amount("0.00"));
    }

    @Test
    void freeDeliveryThresholdIsCheckedAfterTheDiscount() {
        Coupon tenPercent = coupon(DiscountType.PERCENTAGE, "10", "0", null);

        PriceBreakdown price = calculator.calculate(food("800"), OrderType.DELIVERY, tenPercent);

        assertThat(price.discount()).isEqualTo(amount("80.00"));
        assertThat(price.deliveryFee()).isEqualTo(amount("39.90"));
        assertThat(price.total()).isEqualTo(amount("759.90"));
    }

    @Test
    void capsPercentageDiscounts() {
        Coupon capped = coupon(DiscountType.PERCENTAGE, "10", "0", "150");

        PriceBreakdown price = calculator.calculate(food("2000"), OrderType.DINE_IN, capped);

        assertThat(price.discount()).isEqualTo(amount("150.00"));
    }

    @Test
    void fixedDiscountNeverExceedsTheSubtotal() {
        Coupon fifty = coupon(DiscountType.FIXED_AMOUNT, "50", "0", null);

        PriceBreakdown price = calculator.calculate(food("30"), OrderType.TAKEAWAY, fifty);

        assertThat(price.discount()).isEqualTo(amount("30.00"));
        assertThat(price.total()).isEqualTo(amount("0.00"));
        assertThat(price.tax()).isEqualTo(amount("0.00"));
    }

    @Test
    void ordersPlacedBeforePricesIncludedVatAreRepricedTheOldWay() {
        PriceBreakdown price = calculator.calculateBeforeTax(amount("500"), OrderType.TAKEAWAY, null, null);

        assertThat(price.taxIncluded()).isFalse();
        assertThat(price.tax()).isEqualTo(amount("50.00"));
        assertThat(price.total()).isEqualTo(amount("550.00"));
        assertThat(price.vat()).isEmpty();
    }

    private static List<TaxedAmount> food(String gross) {
        return List.of(line("10", gross));
    }

    private static TaxedAmount line(String rate, String gross) {
        return new TaxedAmount(rate(rate), amount(gross));
    }

    private static Coupon coupon(DiscountType type, String value, String minimum, String cap) {
        return new Coupon("TEST", "test", type, new BigDecimal(value), new BigDecimal(minimum),
                cap == null ? null : new BigDecimal(cap), null);
    }

    private static BigDecimal rate(String value) {
        return new BigDecimal(value).setScale(2);
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
