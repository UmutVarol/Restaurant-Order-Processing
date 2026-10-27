package com.umutvarol.restaurant.order;

/**
 * Decides whether an order's bill has been paid, so that the order may be completed (served and paid for,
 * picked up, or delivered).
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
}
