package com.umutvarol.restaurant.menu;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.money.Money;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    @OneToMany(mappedBy = "menuItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private List<OptionGroup> optionGroups = new ArrayList<>();

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

    static final int MAX_GROUPS = 8;
    static final int MAX_CHOICES = 12;

    /**
     * Replaces the dish's options. Orders placed earlier keep the options they were placed with, because order
     * lines copy them.
     *
     * @throws BusinessRuleException if a group is empty, its limits do not fit its choices, or names repeat
     */
    public void replaceOptions(List<OptionGroupSpec> specs) {
        List<OptionGroupSpec> groups = specs == null ? List.of() : specs;
        if (groups.size() > MAX_GROUPS) {
            throw invalidOptions("A dish can have at most %d option groups".formatted(MAX_GROUPS));
        }
        Set<String> groupNames = new HashSet<>();
        for (OptionGroupSpec spec : groups) {
            String groupName = spec.name() == null ? "" : spec.name().strip();
            int count = spec.choices() == null ? 0 : spec.choices().size();
            if (groupName.isEmpty() || !groupNames.add(groupName.toLowerCase(Locale.ROOT))) {
                throw invalidOptions("Option groups need distinct names");
            }
            if (count == 0 || count > MAX_CHOICES) {
                throw invalidOptions("\"%s\" needs between 1 and %d choices".formatted(groupName, MAX_CHOICES));
            }
            if (spec.minSelect() < 0 || spec.maxSelect() < 1 || spec.minSelect() > spec.maxSelect() || spec.maxSelect() > count) {
                throw invalidOptions("\"%s\": choose between %d and %d of %d choices is not possible"
                        .formatted(groupName, spec.minSelect(), spec.maxSelect(), count));
            }
            Set<String> choiceNames = new HashSet<>();
            for (OptionGroupSpec.Choice choice : spec.choices()) {
                String choiceName = choice.name() == null ? "" : choice.name().strip();
                if (choiceName.isEmpty() || !choiceNames.add(choiceName.toLowerCase(Locale.ROOT))) {
                    throw invalidOptions("\"%s\" has an empty or repeated choice".formatted(groupName));
                }
                if (choice.priceDelta() == null || choice.priceDelta().signum() < 0) {
                    throw invalidOptions("A choice cannot lower the price; use a discount instead");
                }
            }
        }
        optionGroups.clear();
        int groupOrder = 0;
        for (OptionGroupSpec spec : groups) {
            OptionGroup group = new OptionGroup(this, spec.name(), spec.minSelect(), spec.maxSelect(), groupOrder++);
            int choiceOrder = 0;
            for (OptionGroupSpec.Choice choice : spec.choices()) {
                group.addChoice(new OptionChoice(group, choice.name(), choice.priceDelta(), choiceOrder++));
            }
            optionGroups.add(group);
        }
    }

    /**
     * Works out which choices a line gets: the ones asked for, checked against this dish's groups and their
     * limits, plus the first choices of any required group the waiter left alone.
     *
     * @param choiceIds what was picked; {@code null} or empty means "the defaults"
     * @throws BusinessRuleException if a choice does not belong to this dish or a group gets too many
     */
    public List<OptionChoice> resolveOptions(Collection<Long> choiceIds) {
        Set<Long> wanted = choiceIds == null ? Set.of() : new LinkedHashSet<>(choiceIds);
        Map<Long, OptionChoice> byId = new HashMap<>();
        optionGroups.forEach(group -> group.getChoices().forEach(choice -> byId.put(choice.getId(), choice)));
        for (Long choiceId : wanted) {
            if (!byId.containsKey(choiceId)) {
                throw new BusinessRuleException("OPTION_NOT_FOUND", "That option does not belong to %s".formatted(name),
                        Map.of("menuItemId", String.valueOf(id), "name", name));
            }
        }
        List<OptionChoice> resolved = new ArrayList<>();
        for (OptionGroup group : optionGroups) {
            List<OptionChoice> picked = group.getChoices().stream().filter(choice -> wanted.contains(choice.getId())).toList();
            if (picked.size() > group.getMaxSelect()) {
                throw new BusinessRuleException("OPTION_LIMIT", "%s allows at most %d choices".formatted(group.getName(),
                        group.getMaxSelect()), Map.of("group", group.getName(), "max", group.getMaxSelect()));
            }
            if (picked.size() < group.getMinSelect()) {
                List<OptionChoice> defaults = new ArrayList<>(picked);
                for (OptionChoice choice : group.getChoices()) {
                    if (defaults.size() >= group.getMinSelect()) {
                        break;
                    }
                    if (!defaults.contains(choice)) {
                        defaults.add(choice);
                    }
                }
                picked = defaults;
            }
            resolved.addAll(picked);
        }
        return resolved;
    }

    private static BusinessRuleException invalidOptions(String message) {
        return new BusinessRuleException("MENU_OPTIONS_INVALID", message);
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

    public List<OptionGroup> getOptionGroups() {
        return Collections.unmodifiableList(optionGroups);
    }

    public long getVersion() {
        return version;
    }
}
