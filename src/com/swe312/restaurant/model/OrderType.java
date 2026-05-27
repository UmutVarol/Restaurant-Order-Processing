package com.swe312.restaurant.model;

public enum OrderType {
    DINE_IN("Dine In"),
    DELIVERY("Delivery");

    private final String displayName;

    OrderType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
