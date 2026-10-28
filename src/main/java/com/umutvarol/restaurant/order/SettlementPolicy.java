package com.umutvarol.restaurant.order;

/**
 * Decides whether an order's bill has been paid, so that the order may be completed (served and paid for,
 * picked up, or delivered), and whether an order may still be made cheaper.
 * <p>
 * Defined here and implemented by the payment module, so that orders never depend on payments: the
 * dependency points one way only, from payments to orders.
 */
public interface SettlementPolicy {

    /**
     * @throws com.umutvarol.restaurant.common.error.BusinessRuleException with code {@code BILL_NOT_PAID}
     *                                                                     if the order's bill still has an amount due
     */
    void requireSettled(Order order);

    /**
     * Called after an order's total went down (a void, a comp, a discount), still inside the transaction.
     *
     * @throws com.umutvarol.restaurant.common.error.BusinessRuleException with code {@code ADJUSTMENT_BELOW_PAID} if more
     *                                                                     has been paid or is being paid than the bill
     *                                                                     would now come to, or
     *                                                                     {@link com.umutvarol.restaurant.common.error.InvalidStateException}
     *                                                                     {@code BILL_NOT_OPEN} if the bill is closed
     */
    void requireCovered(Order order);
}
