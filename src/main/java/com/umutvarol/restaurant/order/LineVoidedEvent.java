package com.umutvarol.restaurant.order;

/** Units of a dish the kitchen was already making were voided: its station gets a void slip. */
public record LineVoidedEvent(Long orderId, Long lineId, int units) {
}
