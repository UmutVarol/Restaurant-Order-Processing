package com.umutvarol.restaurant.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The ledger can be appended to and read; there is deliberately no update or delete. */
interface StockMovementRepository extends Repository<StockMovement, Long> {

    StockMovement save(StockMovement movement);

    boolean existsByOrderId(Long orderId);

    boolean existsByIngredientId(Long ingredientId);

    @Query("select coalesce(sum(m.quantity), 0) from StockMovement m where m.ingredientId = :ingredientId")
    BigDecimal balanceOf(@Param("ingredientId") Long ingredientId);

    /** Ingredient id and balance, for the given ingredients. */
    @Query("select m.ingredientId, sum(m.quantity) from StockMovement m where m.ingredientId in :ids group by m.ingredientId")
    List<Object[]> balancesOf(@Param("ids") Collection<Long> ids);

    List<StockMovement> findByIngredientIdOrderByCreatedAtDescIdDesc(Long ingredientId, Pageable page);

    /** Ingredient id, kind, quantity and value (quantity × price per bulk unit) of each kind of movement in a period. */
    @Query("""
            select m.ingredientId, m.kind, sum(m.quantity), sum(m.quantity * m.unitCost) from StockMovement m
            where m.createdAt >= :from and m.createdAt < :to group by m.ingredientId, m.kind""")
    List<Object[]> totalsBetween(@Param("from") Instant from, @Param("to") Instant to);
}
