package com.umutvarol.restaurant.order.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.umutvarol.restaurant.coupon.Coupon;
import com.umutvarol.restaurant.coupon.DiscountType;
import com.umutvarol.restaurant.order.OrderType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PricingCalculatorTest {

    private final PricingCalculator calculator = new PricingCalculator(
            new PricingProperties(new BigDecimal("0.10"), new BigDecimal("39.90"), new BigDecimal("750.00")));

    @Test
    void addsTaxToTheSubtotal() {
        PriceBreakdown price = calculator.calculate(amount("500"), OrderType.TAKEAWAY, null);

        assertThat(price).isEqualTo(new PriceBreakdown(
                amount("500.00"), amount("0.00"), amount("50.00"), amount("0.00"), amount("550.00")));
    }

    @Test
    void roundsTaxHalfUpToWholeKurus() {
        PriceBreakdown price = calculator.calculate(amount("123.45"), OrderType.DINE_IN, null);

        assertThat(price.tax()).isEqualTo(amount("12.35"));
        assertThat(price.total()).isEqualTo(amount("135.80"));
    }

    @Test
    void chargesDeliveryFeeBelowTheFreeDeliveryThreshold() {
        PriceBreakdown price = calculator.calculate(amount("500"), OrderType.DELIVERY, null);

        assertThat(price.deliveryFee()).isEqualTo(amount("39.90"));
        assertThat(price.total()).isEqualTo(amount("589.90"));
        assertThat(calculator.amountUntilFreeDelivery(price, OrderType.DELIVERY)).isEqualTo(amount("250.00"));
    }

    @Test
    void deliversForFreeFromTheThresholdOn() {
        PriceBreakdown price = calculator.calculate(amount("750"), OrderType.DELIVERY, null);

        assertThat(price.deliveryFee()).isEqualTo(amount("0.00"));
        assertThat(calculator.amountUntilFreeDelivery(price, OrderType.DELIVERY)).isEqualTo(amount("0.00"));
    }

    @Test
    void neverChargesDeliveryForOtherOrderTypes() {
        PriceBreakdown price = calculator.calculate(amount("100"), OrderType.TAKEAWAY, null);

        assertThat(price.deliveryFee()).isEqualTo(amount("0.00"));
        assertThat(calculator.amountUntilFreeDelivery(price, OrderType.TAKEAWAY)).isEqualTo(amount("0.00"));
    }

    @Test
    void freeDeliveryThresholdIsCheckedAfterTheDiscount() {
        Coupon tenPercent = coupon(DiscountType.PERCENTAGE, "10", "0", null);

        PriceBreakdown price = calculator.calculate(amount("800"), OrderType.DELIVERY, tenPercent);

        assertThat(price.discount()).isEqualTo(amount("80.00"));
        assertThat(price.deliveryFee()).isEqualTo(amount("39.90"));
        assertThat(price.tax()).isEqualTo(amount("72.00"));
        assertThat(price.total()).isEqualTo(amount("831.90"));
    }

    @Test
    void capsPercentageDiscounts() {
        Coupon capped = coupon(DiscountType.PERCENTAGE, "10", "0", "150");

        PriceBreakdown price = calculator.calculate(amount("2000"), OrderType.DINE_IN, capped);

        assertThat(price.discount()).isEqualTo(amount("150.00"));
    }

    @Test
    void fixedDiscountNeverExceedsTheSubtotal() {
        Coupon fifty = coupon(DiscountType.FIXED_AMOUNT, "50", "0", null);

        PriceBreakdown price = calculator.calculate(amount("30"), OrderType.TAKEAWAY, fifty);

        assertThat(price.discount()).isEqualTo(amount("30.00"));
        assertThat(price.total()).isEqualTo(amount("0.00"));
    }

    private static Coupon coupon(DiscountType type, String value, String minimum, String cap) {
        return new Coupon("TEST", "test", type, new BigDecimal(value), new BigDecimal(minimum),
                cap == null ? null : new BigDecimal(cap), null);
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
