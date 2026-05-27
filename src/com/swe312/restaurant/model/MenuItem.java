package com.swe312.restaurant.model;

public class MenuItem {
    private final int id;
    private final String name;
    private final Category category;
    private final double price;

    public MenuItem(int id, String name, Category category, double price) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.price = price;
    }

    public int getId() {
        return id;
    }
    
    public String getName() {
        return name;
    }

    public Category getCategory() {
        return category;
    }

    public double getPrice() {
        return price;
    }

    @Override
    public String toString() {
        return name + " - " + Math.round(price) + " TL";
    }
}