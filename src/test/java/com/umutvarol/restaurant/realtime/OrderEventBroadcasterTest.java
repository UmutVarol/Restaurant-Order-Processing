package com.umutvarol.restaurant.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.umutvarol.restaurant.common.error.TooManyRequestsException;
import org.junit.jupiter.api.Test;

class OrderEventBroadcasterTest {

    @Test
    void capsStreamsPerUser() {
        OrderEventBroadcaster broadcaster = new OrderEventBroadcaster(new RealtimeProperties(10, 2));
        broadcaster.subscribe("chef");
        broadcaster.subscribe("chef");

        assertThatThrownBy(() -> broadcaster.subscribe("chef"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasFieldOrPropertyWithValue("code", "TOO_MANY_STREAMS");
        broadcaster.subscribe("waiter");
        assertThat(broadcaster.subscriberCount()).isEqualTo(3);
    }

    @Test
    void capsStreamsAcrossTheServer() {
        OrderEventBroadcaster broadcaster = new OrderEventBroadcaster(new RealtimeProperties(2, 5));
        broadcaster.subscribe("a");
        broadcaster.subscribe("b");

        assertThatThrownBy(() -> broadcaster.subscribe("c")).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void shutdownClosesEveryStreamSoGracefulShutdownIsNotHeldUp() {
        OrderEventBroadcaster broadcaster = new OrderEventBroadcaster(new RealtimeProperties(10, 5));
        broadcaster.subscribe("chef");
        broadcaster.subscribe("waiter");

        broadcaster.closeAll();

        assertThat(broadcaster.subscriberCount()).isZero();
    }
}
