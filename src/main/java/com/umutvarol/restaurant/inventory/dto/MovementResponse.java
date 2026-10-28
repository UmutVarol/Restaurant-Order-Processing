package com.umutvarol.restaurant.inventory.dto;

import com.umutvarol.restaurant.inventory.MovementKind;
import com.umutvarol.restaurant.inventory.StockMovement;
import java.math.BigDecimal;
import java.time.Instant;

/** @param quantity signed, in base units */
public record MovementResponse(Long id, Long ingredientId, MovementKind kind, BigDecimal quantity, BigDecimal unitCost,
                               Long orderId, String reason, String supplier, String by, Instant at) {

    public static MovementResponse from(StockMovement movement) {
        return new MovementResponse(movement.getId(), movement.getIngredientId(), movement.getKind(),
                movement.getQuantity(), movement.getUnitCost(), movement.getOrderId(), movement.getReason(),
                movement.getSupplier(), movement.getActor(), movement.getCreatedAt());
    }
}
