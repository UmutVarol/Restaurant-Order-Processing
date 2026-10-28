package com.umutvarol.restaurant.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jspecify.annotations.Nullable;

/** Something the kitchen buys and uses. How much there is lives in the ledger ({@link StockMovement}), not here. */
@Entity
@Table(name = "ingredients")
public class Ingredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private StockUnit unit;

    /** Per kilogram, litre or piece. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal cost;

    /** In base units. */
    @Column(name = "low_stock_level", precision = 14, scale = 3)
    private @Nullable BigDecimal lowStockLevel;

    @Column(name = "track_stock", nullable = false)
    private boolean trackStock = true;

    @Column(nullable = false)
    private boolean archived;

    @Version
    private long version;

    protected Ingredient() {
        // for JPA
    }

    Ingredient(String name, StockUnit unit, BigDecimal cost, @Nullable BigDecimal lowStockLevel, boolean trackStock) {
        this.unit = unit;
        update(name, cost, lowStockLevel, trackStock);
    }

    void update(String name, BigDecimal cost, @Nullable BigDecimal lowStockLevel, boolean trackStock) {
        this.name = name.strip();
        this.cost = cost;
        this.lowStockLevel = lowStockLevel;
        this.trackStock = trackStock;
    }

    void changeUnit(StockUnit unit) {
        this.unit = unit;
    }

    /** A delivery at a new price becomes the price used for costing from now on. */
    void repriced(BigDecimal cost) {
        this.cost = cost;
    }

    void archive() {
        this.archived = true;
    }

    /** What {@code quantity} base units are worth at the current price. */
    BigDecimal valueOf(BigDecimal quantity) {
        return quantity.multiply(cost).divide(unit.perBulkUnit(), 4, RoundingMode.HALF_UP);
    }

    boolean isLow(BigDecimal balance) {
        return trackStock && lowStockLevel != null && balance.compareTo(lowStockLevel) <= 0;
    }

    boolean isOut(BigDecimal balance) {
        return trackStock && balance.signum() <= 0;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public StockUnit getUnit() {
        return unit;
    }

    public BigDecimal getCost() {
        return cost;
    }

    public @Nullable BigDecimal getLowStockLevel() {
        return lowStockLevel;
    }

    public boolean isTrackStock() {
        return trackStock;
    }

    public boolean isArchived() {
        return archived;
    }
}
