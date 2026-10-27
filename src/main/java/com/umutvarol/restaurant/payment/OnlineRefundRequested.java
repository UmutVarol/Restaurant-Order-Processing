package com.umutvarol.restaurant.payment;

/** An online refund was recorded and should be sent to the provider once the transaction commits. */
record OnlineRefundRequested(Long refundId) {
}
