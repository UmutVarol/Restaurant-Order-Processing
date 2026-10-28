package com.umutvarol.restaurant.order;

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

/** A choice made for an order line, copied from the menu when the order was placed ("Spice level: Hot"). */
@Entity
@Table(name = "order_line_options")
public class OrderLineOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_line_id")
    private OrderLine line;

    @Column(name = "choice_id")
    private Long choiceId;

    @Column(name = "group_name", nullable = false, length = 60)
    private String groupName;

    @Column(name = "choice_name", nullable = false, length = 60)
    private String choiceName;

    @Column(name = "price_delta", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceDelta;

    protected OrderLineOption() {
        // for JPA
    }

    OrderLineOption(OrderLine line, Long choiceId, String groupName, String choiceName, BigDecimal priceDelta) {
        this.line = line;
        this.choiceId = choiceId;
        this.groupName = groupName;
        this.choiceName = choiceName;
        this.priceDelta = priceDelta;
    }

    public Long getChoiceId() {
        return choiceId;
    }

    public String getGroupName() {
        return groupName;
    }

    public String getChoiceName() {
        return choiceName;
    }

    public BigDecimal getPriceDelta() {
        return priceDelta;
    }
}
