package com.umutvarol.restaurant.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * One line of the stock ledger. Movements are written once and never changed or deleted ({@link Immutable}, and
 * the repository offers no way to): a mistake is corrected by another movement, and the balance is always the sum.
 */
@Entity
@Immutable
@Table(name = "stock_movements")
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ingredient_id", nullable = false)
    private Long ingredientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private MovementKind kind;

    /** Signed, in base units: deliveries add, sales and waste take away. */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    /** Per kilogram, litre or piece, at the time. */
    @Column(name = "unit_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "order_id")
    private @Nullable Long orderId;

    @Column(name = "sale_key", length = 60, unique = true)
    private @Nullable String saleKey;

    @Column(length = 200)
    private @Nullable String reason;

    @Column(length = 80)
    private @Nullable String supplier;

    @Column(nullable = false, length = 50)
    private String actor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StockMovement() {
        // for JPA
    }

    private StockMovement(Ingredient ingredient, MovementKind kind, BigDecimal quantity, BigDecimal unitCost,
                          @Nullable Long orderId, @Nullable String reason, @Nullable String supplier, String actor,
                          Instant at) {
        this.ingredientId = ingredient.getId();
        this.kind = kind;
        this.quantity = quantity;
        this.unitCost = unitCost;
        this.orderId = orderId;
        this.saleKey = orderId == null ? null : "order:%d:%d".formatted(orderId, ingredient.getId());
        this.reason = reason;
        this.supplier = supplier;
        this.actor = actor;
        this.createdAt = at;
    }

    static StockMovement sale(Ingredient ingredient, BigDecimal used, long orderId, Instant at) {
        return new StockMovement(ingredient, MovementKind.SALE, used.negate(), ingredient.getCost(), orderId, null, null,
                "system", at);
    }

    static StockMovement waste(Ingredient ingredient, BigDecimal wasted, String reason, String actor, Instant at) {
        return new StockMovement(ingredient, MovementKind.WASTE, wasted.negate(), ingredient.getCost(), null, reason, null,
                actor, at);
    }

    static StockMovement delivery(Ingredient ingredient, BigDecimal received, BigDecimal unitCost,
                                  @Nullable String supplier, @Nullable String note, String actor, Instant at) {
        return new StockMovement(ingredient, MovementKind.DELIVERY, received, unitCost, null, note, supplier, actor, at);
    }

    /** @param difference what was counted minus what the ledger said: negative when stock went missing */
    static StockMovement count(Ingredient ingredient, BigDecimal difference, @Nullable String note, String actor,
                               Instant at) {
        return new StockMovement(ingredient, MovementKind.COUNT, difference, ingredient.getCost(), null, note, null, actor,
                at);
    }

    public Long getId() {
        return id;
    }

    public Long getIngredientId() {
        return ingredientId;
    }

    public MovementKind getKind() {
        return kind;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }

    public @Nullable Long getOrderId() {
        return orderId;
    }

    public @Nullable String getReason() {
        return reason;
    }

    public @Nullable String getSupplier() {
        return supplier;
    }

    public String getActor() {
        return actor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
