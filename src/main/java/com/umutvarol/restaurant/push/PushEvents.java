package com.umutvarol.restaurant.push;

import com.umutvarol.restaurant.guest.GuestRequestChangedEvent;
import com.umutvarol.restaurant.guest.GuestRequestStatus;
import com.umutvarol.restaurant.order.OrderChangedEvent;
import com.umutvarol.restaurant.order.OrderStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Turns saved changes into notifications: a new order, an order ready, a guest calling. */
@Component
class PushEvents {

    private final PushService push;

    PushEvents(PushService push) {
        this.push = push;
    }

    @TransactionalEventListener
    void order(OrderChangedEvent event) {
        PushTopic topic = event.kind() == OrderChangedEvent.Kind.CREATED ? PushTopic.NEW_ORDER
                : event.kind() == OrderChangedEvent.Kind.STATUS_CHANGED && event.status() == OrderStatus.READY ? PushTopic.READY
                : null;
        if (topic == null) {
            return;
        }
        push.notify(topic, event.branchId(), lang -> PushMessages.order(lang, topic, event.orderId(), event.orderType().name(),
                event.tableCode(), event.customerName()), topic == PushTopic.NEW_ORDER ? "#/kitchen" : "#/service",
                "order-" + event.orderId());
    }

    @TransactionalEventListener
    void guest(GuestRequestChangedEvent event) {
        if (event.status() != GuestRequestStatus.OPEN) {
            return;
        }
        push.notify(PushTopic.GUEST_CALL, event.branchId(), lang -> PushMessages.guest(lang, event.kind().name(), event.tableCode()),
                "#/service", "guest-" + event.id());
    }
}
