package com.umutvarol.restaurant.order;

import static com.umutvarol.restaurant.order.OrderStatus.CANCELLED;
import static com.umutvarol.restaurant.order.OrderStatus.COMPLETED;
import static com.umutvarol.restaurant.order.OrderStatus.OUT_FOR_DELIVERY;
import static com.umutvarol.restaurant.order.OrderStatus.PENDING;
import static com.umutvarol.restaurant.order.OrderStatus.PREPARING;
import static com.umutvarol.restaurant.order.OrderStatus.READY;
import static com.umutvarol.restaurant.order.OrderStatus.SERVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class OrderStatusTest {

    @DisplayName("each order type follows its own path through the lifecycle")
    @ParameterizedTest(name = "{0}")
    @MethodSource("lifecycles")
    void followsTheLifecycleOfItsType(OrderType type, List<OrderStatus> expectedPath) {
        List<OrderStatus> path = new ArrayList<>(List.of(PENDING));
        Optional<OrderStatus> next = PENDING.next(type);
        while (next.isPresent()) {
            path.add(next.get());
            next = next.get().next(type);
        }

        assertThat(path).containsExactlyElementsOf(expectedPath);
    }

    static Stream<Arguments> lifecycles() {
        return Stream.of(
                arguments(OrderType.DINE_IN, List.of(PENDING, PREPARING, READY, SERVED, COMPLETED)),
                arguments(OrderType.TAKEAWAY, List.of(PENDING, PREPARING, READY, COMPLETED)),
                arguments(OrderType.DELIVERY, List.of(PENDING, PREPARING, READY, OUT_FOR_DELIVERY, COMPLETED)));
    }

    @ParameterizedTest
    @EnumSource(OrderType.class)
    void terminalStatusesHaveNoSuccessor(OrderType type) {
        assertThat(COMPLETED.next(type)).isEmpty();
        assertThat(CANCELLED.next(type)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(names = {"PENDING", "PREPARING"})
    void ordersCanBeCancelledUntilTheKitchenIsDone(OrderStatus status) {
        assertThat(status.isCancellable()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(names = {"READY", "SERVED", "OUT_FOR_DELIVERY", "COMPLETED", "CANCELLED"})
    void finishedOrdersCannotBeCancelled(OrderStatus status) {
        assertThat(status.isCancellable()).isFalse();
    }

    @Test
    void activeStatusesExcludeTerminalOnes() {
        assertThat(OrderStatus.activeStatuses())
                .containsExactlyInAnyOrder(PENDING, PREPARING, READY, SERVED, OUT_FOR_DELIVERY)
                .allMatch(OrderStatus::isActive);
        assertThat(COMPLETED.isActive()).isFalse();
        assertThat(CANCELLED.isActive()).isFalse();
    }
}
