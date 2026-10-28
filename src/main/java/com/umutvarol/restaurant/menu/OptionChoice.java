package com.umutvarol.restaurant.menu;

import com.umutvarol.restaurant.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** One answer in an {@link OptionGroup}, with what it adds to the dish's price (zero for most). */
@Entity
@Table(name = "option_choices")
public class OptionChoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id")
    private OptionGroup group;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "price_delta", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceDelta;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected OptionChoice() {
        // for JPA
    }

    OptionChoice(OptionGroup group, String name, BigDecimal priceDelta, int sortOrder) {
        this.group = group;
        this.name = name.strip();
        this.priceDelta = Money.normalize(priceDelta);
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public OptionGroup getGroup() {
        return group;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPriceDelta() {
        return priceDelta;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
