package com.umutvarol.restaurant.menu;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A choice the guest makes for a dish, such as "Spice level" (pick exactly one) or "Extras" (pick up to three).
 * A group with {@code minSelect > 0} is required; when the waiter does not choose, its first choices are taken.
 */
@Entity
@Table(name = "option_groups")
public class OptionGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_item_id")
    private MenuItem menuItem;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "min_select", nullable = false)
    private int minSelect;

    @Column(name = "max_select", nullable = false)
    private int maxSelect;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<OptionChoice> choices = new ArrayList<>();

    protected OptionGroup() {
        // for JPA
    }

    OptionGroup(MenuItem menuItem, String name, int minSelect, int maxSelect, int sortOrder) {
        this.menuItem = menuItem;
        this.name = name.strip();
        this.minSelect = minSelect;
        this.maxSelect = maxSelect;
        this.sortOrder = sortOrder;
    }

    void addChoice(OptionChoice choice) {
        choices.add(choice);
    }

    public boolean isRequired() {
        return minSelect > 0;
    }

    public Long getId() {
        return id;
    }

    public MenuItem getMenuItem() {
        return menuItem;
    }

    public String getName() {
        return name;
    }

    public int getMinSelect() {
        return minSelect;
    }

    public int getMaxSelect() {
        return maxSelect;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public List<OptionChoice> getChoices() {
        return Collections.unmodifiableList(choices);
    }
}
