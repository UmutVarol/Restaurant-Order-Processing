package com.umutvarol.restaurant.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderRepository;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.order.pricing.PriceBreakdown;
import com.umutvarol.restaurant.security.AppUserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"restaurant.demo.enabled=true", "restaurant.demo.history-days=7"})
@ActiveProfiles("test")
@Transactional
class DemoDataSeederIntegrationTest {

    @Autowired
    private OrderRepository orders;
    @Autowired
    private AppUserRepository users;

    @Test
    void createsTheDemoStaffAccounts() {
        assertThat(users.findAll()).extracting("username").containsExactlyInAnyOrder("admin", "waiter", "chef");
    }

    @Test
    void seedsAWeekOfConsistentOrders() {
        List<Order> all = orders.findAll();

        assertThat(all).hasSizeGreaterThan(100);
        assertThat(all).allSatisfy(order -> {
            PriceBreakdown price = order.getPricing();
            assertThat(price.total()).isEqualByComparingTo(
                    price.subtotal().subtract(price.discount()).add(price.tax()).add(price.deliveryFee()));
            assertThat(order.getHistory().getFirst().getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(order.getHistory().getLast().getStatus()).isEqualTo(order.getStatus());
            assertThat(order.getLines()).isNotEmpty();
        });
        assertThat(all).extracting(Order::getType).contains(OrderType.values());
    }

    @Test
    void keepsTheKitchenBusyWithoutDoubleBookingTables() {
        List<Order> active = orders.findByStatusInOrderByCreatedAtAsc(OrderStatus.activeStatuses());

        assertThat(active).isNotEmpty();
        List<Long> occupiedTables = active.stream()
                .filter(order -> order.getType() == OrderType.DINE_IN)
                .map(order -> order.getTable().getId())
                .toList();
        assertThat(occupiedTables).doesNotHaveDuplicates();
    }
}
