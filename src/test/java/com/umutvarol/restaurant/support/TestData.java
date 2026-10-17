package com.umutvarol.restaurant.support;

import com.umutvarol.restaurant.menu.DietaryTag;
import com.umutvarol.restaurant.menu.MenuCategory;
import com.umutvarol.restaurant.menu.MenuItem;
import com.umutvarol.restaurant.order.OrderDetails;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.order.PaymentMethod;
import com.umutvarol.restaurant.table.DiningTable;
import com.umutvarol.restaurant.table.TableZone;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.test.util.ReflectionTestUtils;

/** Builders for domain objects used across unit tests. */
public final class TestData {

    private TestData() {
    }

    public static MenuItem menuItem(long id, String name, MenuCategory category, String price) {
        MenuItem item = new MenuItem(name, name + " description", category, new BigDecimal(price), "🍽️", 10, false,
                Set.<DietaryTag>of());
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    public static DiningTable table(long id, String code) {
        DiningTable table = new DiningTable(code, 4, TableZone.INDOOR);
        ReflectionTestUtils.setField(table, "id", id);
        return table;
    }

    public static OrderDetails dineIn(DiningTable table) {
        return new OrderDetails(OrderType.DINE_IN, "Ayşe Kaya", null, table, null, null, PaymentMethod.CARD, null);
    }

    public static OrderDetails takeaway() {
        return new OrderDetails(OrderType.TAKEAWAY, "Mehmet Yıldız", null, null, null, null, PaymentMethod.CASH, null);
    }

    public static OrderDetails delivery(String address) {
        return new OrderDetails(OrderType.DELIVERY, "Zeynep Arslan", "+90 532 000 00 00", null, address, null,
                PaymentMethod.ONLINE, null);
    }
}
