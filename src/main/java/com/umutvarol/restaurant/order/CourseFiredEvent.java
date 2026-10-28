package com.umutvarol.restaurant.order;

/** A held course was sent to the kitchen: its stations' printers print it now. */
public record CourseFiredEvent(Long orderId, int course) {
}
