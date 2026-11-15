package com.umutvarol.restaurant.printing;

import org.hibernate.annotations.Filter;
import jakarta.persistence.EntityListeners;
import com.umutvarol.restaurant.business.BranchStamp;
import com.umutvarol.restaurant.business.BranchOwned;
import com.umutvarol.restaurant.business.Branch;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/** A ticket printer on the restaurant's network, and which kitchen stations it prints for (kitchen, bar, desserts). */
@Entity
@Table(name = "printers")
@Filter(name = Branch.FILTER)
@EntityListeners(BranchStamp.class)
public class Printer implements BranchOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The business this belongs to; Hibernate filters every query by it (docs/adr/0001-multi-business.md). */
    @TenantId
    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    /** The branch this happened in (BranchOwned); lists are limited to it when a request works in one branch. */
    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(nullable = false, unique = true, length = 40)
    private String name;

    @Column(nullable = false, length = 100)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(name = "paper_width", nullable = false)
    private int paperWidth;

    @Column(nullable = false, length = 200)
    private String stations;

    @Column(nullable = false)
    private boolean enabled;

    /** Printed by the branch's print bridge instead of by the server itself (always so for Sofra Cloud). */
    @Column(name = "via_bridge", nullable = false)
    private boolean viaBridge;

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

    void update(String name, String host, int port, int paperWidth, Set<String> stations, boolean enabled, boolean viaBridge) {
        this.viaBridge = viaBridge;
        this.name = name;
        this.host = host;
        this.port = port;
        this.paperWidth = paperWidth;
        this.stations = stations.stream().sorted().collect(Collectors.joining(","));
        this.enabled = enabled;
    }

    void printed(Instant at) {
        this.lastPrintedAt = at;
        this.lastError = null;
    }

    void failed(String error) {
        this.lastError = error.length() > 255 ? error.substring(0, 255) : error;
    }

    /** Whether this printer gets the dishes of the given station. */
    public boolean prints(String stationCode) {
        return enabled && stations().contains(stationCode);
    }

    public Set<String> stations() {
        return stations == null || stations.isBlank() ? Set.of()
                : Arrays.stream(stations.split(",")).collect(Collectors.toCollection(TreeSet::new));
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

    public boolean isViaBridge() {
        return viaBridge;
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

    @Override
    public @Nullable Long getBranchId() {
        return branchId;
    }

    @Override
    public void assignBranch(long branchId) {
        this.branchId = branchId;
    }
}
