package com.umutvarol.restaurant.analytics;

import com.umutvarol.restaurant.order.OrderLine;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Read-only aggregate queries over order lines, computed in the database. */
interface SalesReportRepository extends Repository<OrderLine, Long> {

    @Query("""
            select new com.umutvarol.restaurant.analytics.ItemSales(
                       l.itemName, l.itemIcon, sum(l.quantity - l.voidedQuantity), sum(l.lineTotal))
            from OrderLine l join l.order o
            where o.createdAt >= :from and o.createdAt < :to
              and o.status <> com.umutvarol.restaurant.order.OrderStatus.CANCELLED
            group by l.itemName, l.itemIcon
            order by sum(l.quantity - l.voidedQuantity) desc, sum(l.lineTotal) desc
            """)
    List<ItemSales> topItems(Instant from, Instant to, Limit limit);

    @Query("""
            select new com.umutvarol.restaurant.analytics.CategorySales(
                       l.category, sum(l.quantity - l.voidedQuantity), sum(l.lineTotal))
            from OrderLine l join l.order o
            where o.createdAt >= :from and o.createdAt < :to
              and o.status <> com.umutvarol.restaurant.order.OrderStatus.CANCELLED
            group by l.category
            order by sum(l.lineTotal) desc
            """)
    List<CategorySales> salesByCategory(Instant from, Instant to);
}
