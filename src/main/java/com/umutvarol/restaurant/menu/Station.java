package com.umutvarol.restaurant.menu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A place in the kitchen where dishes are made (cold kitchen, grill, desserts, bar). Dishes are routed to their
 * station's printer and kitchen screen; {@code code} is the stable key the rest of the system uses.
 */
@Entity
@Table(name = "stations")
public class Station {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, unique = true, length = 40)
    private String name;

    @Column(name = "target_minutes", nullable = false)
    private int targetMinutes;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected Station() {
        // for JPA
    }

    Station(String code, String name, int targetMinutes, int sortOrder) {
        this.code = code;
        this.name = name;
        this.targetMinutes = targetMinutes;
        this.sortOrder = sortOrder;
    }

    void update(String name, int targetMinutes) {
        this.name = name;
        this.targetMinutes = targetMinutes;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getTargetMinutes() {
        return targetMinutes;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    /** Where a dish goes when it has no station of its own: by its menu category. */
    public static String defaultCode(MenuCategory category) {
        return switch (category) {
            case STARTER -> "COLD";
            case MAIN -> "HOT";
            case DESSERT -> "DESSERT";
            case DRINK -> "BAR";
        };
    }
}
