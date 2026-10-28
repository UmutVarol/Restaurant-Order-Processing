package com.umutvarol.restaurant.printing;

import com.umutvarol.restaurant.menu.MenuCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** A ticket printer on the restaurant's network, and which menu categories it prints (kitchen, bar, dessert). */
@Entity
@Table(name = "printers")
public class Printer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String name;

    @Column(nullable = false, length = 100)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(name = "paper_width", nullable = false)
    private int paperWidth;

    @Column(nullable = false, length = 100)
    private String categories;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_printed_at")
    private Instant lastPrintedAt;

    @Column(name = "last_error", length = 255)
    private String lastError;

    protected Printer() {
        // for JPA
    }

    Printer(Instant at) {
        this.createdAt = at;
    }

    void update(String name, String host, int port, int paperWidth, Set<MenuCategory> categories, boolean enabled) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.paperWidth = paperWidth;
        this.categories = categories.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        this.enabled = enabled;
    }

    void printed(Instant at) {
        this.lastPrintedAt = at;
        this.lastError = null;
    }

    void failed(String error) {
        this.lastError = error.length() > 255 ? error.substring(0, 255) : error;
    }

    public boolean prints(MenuCategory category) {
        return enabled && categories().contains(category);
    }

    public Set<MenuCategory> categories() {
        return categories == null || categories.isBlank() ? EnumSet.noneOf(MenuCategory.class)
                : Arrays.stream(categories.split(",")).map(MenuCategory::valueOf)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(MenuCategory.class)));
    }

    /** Characters per line: 32 on 58 mm paper, 48 on 80 mm. */
    public int columns() {
        return paperWidth == 58 ? 32 : 48;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getPaperWidth() {
        return paperWidth;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable Instant getLastPrintedAt() {
        return lastPrintedAt;
    }

    public @Nullable String getLastError() {
        return lastError;
    }
}
