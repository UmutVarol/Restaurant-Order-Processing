package com.umutvarol.restaurant.menu;

import com.umutvarol.restaurant.common.money.Money;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A dish or drink that can be ordered. Items referenced by past orders are never physically deleted —
 * they are {@link #archive() archived} so that order history stays intact.
 */
@Entity
@Table(name = "menu_items")
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String name;

    @Column(length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MenuCategory category;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(length = 16)
    private String icon;

    @Column(name = "prep_time_minutes", nullable = false)
    private int prepTimeMinutes;

    @Column(nullable = false)
    private boolean popular;

    @Column(nullable = false)
    private boolean available = true;

    @Column(nullable = false)
    private boolean archived;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "menu_item_tags", joinColumns = @JoinColumn(name = "menu_item_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "tag", nullable = false, length = 20)
    private Set<DietaryTag> tags = new HashSet<>();

    @Version
    private long version;

    protected MenuItem() {
        // for JPA
    }

    public MenuItem(String name, String description, MenuCategory category, BigDecimal price, String icon,
                    int prepTimeMinutes, boolean popular, Set<DietaryTag> tags) {
        update(name, description, category, price, icon, prepTimeMinutes, popular, tags);
    }

    public void update(String name, String description, MenuCategory category, BigDecimal price, String icon,
                       int prepTimeMinutes, boolean popular, Set<DietaryTag> tags) {
        if (!Money.isPositive(price)) {
            throw new IllegalArgumentException("price must be positive");
        }
        this.name = Objects.requireNonNull(name, "name").strip();
        this.description = description == null ? null : description.strip();
        this.category = Objects.requireNonNull(category, "category");
        this.price = Money.normalize(price);
        this.icon = icon;
        this.prepTimeMinutes = prepTimeMinutes;
        this.popular = popular;
        this.tags.clear();
        if (tags != null) {
            this.tags.addAll(tags);
        }
    }

    /** Whether the item can currently be put on a new order. */
    public boolean isOrderable() {
        return available && !archived;
    }

    public void changeAvailability(boolean available) {
        this.available = available;
    }

    public void archive() {
        this.archived = true;
        this.available = false;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public MenuCategory getCategory() {
        return category;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getIcon() {
        return icon;
    }

    public int getPrepTimeMinutes() {
        return prepTimeMinutes;
    }

    public boolean isPopular() {
        return popular;
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isArchived() {
        return archived;
    }

    public Set<DietaryTag> getTags() {
        return Collections.unmodifiableSet(tags);
    }

    public long getVersion() {
        return version;
    }
}
