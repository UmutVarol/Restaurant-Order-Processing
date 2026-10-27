package com.umutvarol.restaurant.payment.dto;

import com.umutvarol.restaurant.order.PaymentMethod;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/**
 * Money taken at the table.
 *
 * @param amount       what goes towards the bill
 * @param tip          on top of the amount; optional
 * @param tendered     cash only: what the guest handed over, so the till can show the change. Defaults to exact
 * @param approvalCode card only: the approval code printed by the card terminal, for matching the bank statement
 */
public record TakePaymentRequest(
        @NotNull PaymentMethod method,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @DecimalMin(value = "0.00") @Digits(integer = 10, fraction = 2) BigDecimal tip,
        @DecimalMin(value = "0.00") @Digits(integer = 10, fraction = 2) BigDecimal tendered,
        @Pattern(regexp = "[A-Za-z0-9]{4,12}", message = "must be 4 to 12 letters or digits") String approvalCode) {

    @AssertTrue(message = "Online payments are taken through a payment link, not at the till.")
    boolean isTakenAtTheTill() {
        return method != PaymentMethod.ONLINE;
    }
}
