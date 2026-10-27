package com.umutvarol.restaurant.payment;

import static com.umutvarol.restaurant.support.TestData.menuItem;
import static com.umutvarol.restaurant.support.TestData.takeaway;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.DomainException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.menu.MenuCategory;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderLine;
import com.umutvarol.restaurant.order.pricing.PriceBreakdown;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BillTest {

    private static final Instant T0 = Instant.parse("2026-05-20T17:30:00Z");

    private final Order soup = order("300.00");
    private final Order kebab = order("700.00");
    private final Bill bill = Bill.openFor(soup, T0);

    {
        bill.addOrder(kebab);
    }

    @Test
    void sumsTheOrdersAndTracksWhatIsStillDue() {
        assertThat(bill.total()).isEqualByComparingTo("1000.00");
        assertThat(bill.balance()).isEqualByComparingTo("1000.00");

        bill.takeCard(money("400"), money("40"), "123456", T0, "waiter", null);

        assertThat(bill.paid()).isEqualByComparingTo("400.00");
        assertThat(bill.tips()).isEqualByComparingTo("40.00");
        assertThat(bill.balance()).isEqualByComparingTo("600.00");
        assertThat(bill.isSettled()).isFalse();
    }

    @Test
    void givesChangeForCashAndRefusesTooLittle() {
        Payment payment = bill.takeCash(money("1000"), money("50"), money("1100"), T0, "waiter", null);
        assertThat(payment.getChangeDue()).isEqualByComparingTo("50.00");

        Bill other = Bill.openFor(order("100.00"), T0);
        assertThatThrownBy(() -> other.takeCash(money("100"), money("10"), money("105"), T0, "waiter", null))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("CASH_NOT_ENOUGH");
    }

    @Test
    void neverTakesMoreThanIsDue() {
        assertThatThrownBy(() -> bill.takeCard(money("1000.01"), Money.ZERO, null, T0, "waiter", null))
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("PAYMENT_EXCEEDS_BALANCE");
        assertThatThrownBy(() -> bill.takeCard(Money.ZERO, Money.ZERO, null, T0, "waiter", null))
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("PAYMENT_AMOUNT_INVALID");
        assertThatThrownBy(() -> bill.takeCard(money("100"), money("100.01"), null, T0, "waiter", null))
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("TIP_INVALID");
    }

    @Test
    void anOnlinePaymentInProgressHoldsItsAmount() {
        Payment online = bill.startOnline(money("600"), Money.ZERO, null, T0, "guest");

        assertThat(bill.available()).isEqualByComparingTo("400.00");
        assertThat(bill.balance()).isEqualByComparingTo("1000.00");
        assertThatThrownBy(() -> bill.takeCash(money("500"), Money.ZERO, money("500"), T0, "waiter", null))
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("PAYMENT_EXCEEDS_BALANCE");

        online.expire(T0.plus(Duration.ofMinutes(30)));
        assertThat(bill.available()).isEqualByComparingTo("1000.00");
    }

    @Test
    void aLateOnlineSuccessStillCounts() {
        Payment online = bill.startOnline(money("1000"), Money.ZERO, null, T0, "guest");
        online.expire(T0.plusSeconds(1800));
        online.succeed("pay-1", T0.plusSeconds(1801));

        assertThat(bill.isSettled()).isTrue();
    }

    @Test
    void refundsWhatACancelledOrderNoLongerOwesNewestPaymentFirst() {
        Payment card = bill.takeCard(money("600"), Money.ZERO, null, T0, "waiter", null);
        Payment online = bill.startOnline(money("400"), Money.ZERO, null, T0.plusSeconds(60), "guest");
        online.succeed("pay-2", T0.plusSeconds(90));

        kebab.cancel("Guest changed their mind", T0.plusSeconds(120), "waiter");
        List<Refund> refunds = bill.refundOverpayment("Order cancelled", T0.plusSeconds(120), "waiter");

        assertThat(refunds).hasSize(2);
        assertThat(refunds.get(0).getPayment()).isSameAs(online);
        assertThat(refunds.get(0).getAmount()).isEqualByComparingTo("400.00");
        assertThat(refunds.get(0).getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(refunds.get(1).getPayment()).isSameAs(card);
        assertThat(refunds.get(1).getAmount()).isEqualByComparingTo("300.00");
        assertThat(refunds.get(1).getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(bill.balance()).isEqualByComparingTo("0.00");
    }

    @Test
    void aRefusedProviderRefundGivesTheMoneyBackToThePayment() {
        Payment online = bill.startOnline(money("1000"), Money.ZERO, null, T0, "guest");
        online.succeed("pay-3", T0);
        Refund refund = bill.refund(online, money("100"), "Cold soup", T0, "admin");
        assertThat(online.refundable()).isEqualByComparingTo("900.00");

        refund.fail("Refused");

        assertThat(online.refundable()).isEqualByComparingTo("1000.00");
        assertThatThrownBy(() -> bill.refund(online, money("1000.01"), "Too much", T0, "admin"))
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("REFUND_EXCEEDS_PAYMENT");
    }

    @Test
    void closesOnceEveryOrderIsDoneAndPaid() {
        bill.takeCard(money("1000"), Money.ZERO, null, T0, "waiter", null);
        assertThat(bill.refresh(T0)).as("orders still in the kitchen").isFalse();

        complete(soup);
        complete(kebab);

        assertThat(bill.refresh(T0.plusSeconds(3600))).isTrue();
        assertThat(bill.getStatus()).isEqualTo(BillStatus.CLOSED);
        assertThatThrownBy(() -> bill.takeCard(money("1"), Money.ZERO, null, T0, "waiter", null))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void aBillWhoseOrdersWereAllCancelledIsVoid() {
        soup.cancel(null, T0, "waiter");
        kebab.cancel(null, T0, "waiter");

        assertThat(bill.refresh(T0)).isTrue();
        assertThat(bill.getStatus()).isEqualTo(BillStatus.VOID);
    }

    @Test
    void aNewRoundReopensTheBill() {
        soup.cancel(null, T0, "waiter");
        kebab.cancel(null, T0, "waiter");
        bill.refresh(T0);

        bill.addOrder(order("90.00"));

        assertThat(bill.getStatus()).isEqualTo(BillStatus.OPEN);
        assertThat(bill.getClosedAt()).isNull();
        assertThat(bill.balance()).isEqualByComparingTo("90.00");
    }

    private static void complete(Order order) {
        while (order.nextStatus().isPresent()) {
            order.advance(T0, "waiter");
        }
    }

    private static Order order(String total) {
        BigDecimal value = new BigDecimal(total);
        return Order.place(takeaway(), List.of(OrderLine.of(menuItem(1, "Item", MenuCategory.MAIN, total), 1, null)),
                new PriceBreakdown(value, Money.ZERO, Money.ZERO, Money.ZERO, value), T0, "waiter");
    }

    private static BigDecimal money(String amount) {
        return Money.of(amount);
    }
}
