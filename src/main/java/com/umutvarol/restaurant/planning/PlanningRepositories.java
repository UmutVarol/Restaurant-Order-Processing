package com.umutvarol.restaurant.planning;

import com.umutvarol.restaurant.order.OrderLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** What the kitchen made and sold, read for the forecast; filtered to the business and branch like every query. */
interface PlanningSalesRepository extends Repository<OrderLine, Long> {

    /** When each order was placed, the dish and how many were made (voided ones were not; comped ones were). */
    @Query("""
            select o.createdAt, l.menuItemId, l.quantity - l.voidedQuantity from OrderLine l join l.order o
            where o.createdAt >= :from and o.createdAt < :to
              and o.status <> com.umutvarol.restaurant.order.OrderStatus.CANCELLED""")
    List<Object[]> madeBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** What guests paid for orders completed in a period, to set waste against. */
    @Query("""
            select coalesce(sum(o.pricing.total), 0) from Order o
            where o.createdAt >= :from and o.createdAt < :to
              and o.status = com.umutvarol.restaurant.order.OrderStatus.COMPLETED""")
    BigDecimal revenueBetween(@Param("from") Instant from, @Param("to") Instant to);
}

interface PrepCheckRepository extends JpaRepository<PrepCheck, Long> {

    List<PrepCheck> findByPrepDate(LocalDate prepDate);

    Optional<PrepCheck> findByPrepDateAndMenuItemId(LocalDate prepDate, Long menuItemId);
}

interface WasteEntryRepository extends JpaRepository<WasteEntry, Long> {

    List<WasteEntry> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(Instant from, Instant to);

    Optional<WasteEntry> findByRequestKey(String requestKey);
}
