package com.umutvarol.restaurant.floor;

import com.umutvarol.restaurant.business.UsageLimits;
import com.umutvarol.restaurant.business.WorkingBranch;
import org.jspecify.annotations.Nullable;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.floor.dto.TableRequest;
import com.umutvarol.restaurant.floor.dto.TableResponse;
import com.umutvarol.restaurant.order.OrderRepository;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.table.DiningTable;
import com.umutvarol.restaurant.table.DiningTableRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Managers set up the dining room: add a table, renumber or resize it, take it off the floor. A removed table keeps
 * its history (orders, bills and reservations point at it) and comes back if a table with its number is added
 * again. A table with guests or a booking cannot be removed or renumbered.
 */
@Service
@Transactional
public class TableAdminService {

    private static final Logger audit = LoggerFactory.getLogger("order.audit");

    private final DiningTableRepository tables;
    private final OrderRepository orders;
    private final ObjectProvider<TableBookings> bookings;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final WorkingBranch branch;
    private final ObjectProvider<UsageLimits> limits;

    public TableAdminService(DiningTableRepository tables, OrderRepository orders, ObjectProvider<TableBookings> bookings,
                             ApplicationEventPublisher events, Clock clock, WorkingBranch branch,
                             ObjectProvider<UsageLimits> limits) {
        this.limits = limits;
        this.branch = branch;
        this.tables = tables;
        this.orders = orders;
        this.bookings = bookings;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TableResponse> list() {
        return tables.findByActiveTrueOrderByIdAsc().stream().map(TableAdminService::toResponse).toList();
    }

    public TableResponse add(TableRequest request, String actor) {
        String code = request.code().strip();
        Optional<DiningTable> existing = tables.findByCodeAndBranchId(code, branch.forNew());
        limits.ifAvailable(plan -> plan.allowOneMore(UsageLimits.Resource.TABLES));
        DiningTable table;
        if (existing.isPresent()) {
            if (existing.get().isActive()) {
                throw taken(code);
            }
            table = existing.get();
            table.restore(request.seats(), request.zone());
        } else {
            table = tables.save(new DiningTable(code, request.seats(), request.zone()));
        }
        audit.info("table.added table={} seats={} zone={} by={}", code, request.seats(), request.zone(), actor);
        changed(table);
        return toResponse(table);
    }

    public TableResponse update(Long id, TableRequest request, String actor) {
        DiningTable table = find(id);
        String code = request.code().strip();
        if (!code.equals(table.getCode())) {
            if (tables.findByCodeAndBranchId(code, table.getBranchId()).isPresent()) {
                throw taken(code);
            }
            // printed QR cards and the kitchen's tickets carry the number: not while guests are there
            requireUnused(table, "renumber");
        }
        table.describe(code, request.seats(), request.zone());
        audit.info("table.changed id={} table={} seats={} zone={} by={}", id, code, request.seats(), request.zone(), actor);
        changed(table);
        return toResponse(table);
    }

    public void remove(Long id, String actor) {
        DiningTable table = find(id);
        requireUnused(table, "remove");
        table.remove();
        audit.info("table.removed table={} by={}", table.getCode(), actor);
        changed(table);
    }

    private void requireUnused(DiningTable table, String action) {
        if (orders.existsByTableIdAndStatusIn(table.getId(), OrderStatus.activeStatuses())
                || bookings.getIfAvailable(() -> TableBookings.NONE).holdsTable(table.getId())) {
            throw new InvalidStateException("TABLE_IN_USE",
                    "Table %s has guests or a booking; %s it later".formatted(table.getCode(), action),
                    Map.of("table", table.getCode()));
        }
    }

    private void changed(DiningTable table) {
        events.publishEvent(new TableChangedEvent(table.getId(), table.getCode(), null, clock.instant(), table.getBranchId()));
    }

    private DiningTable find(Long id) {
        return tables.findById(id).filter(DiningTable::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("table", id));
    }

    private static InvalidStateException taken(String code) {
        return new InvalidStateException("TABLE_CODE_TAKEN", "There is already a table %s".formatted(code),
                Map.of("table", code));
    }

    private static TableResponse toResponse(DiningTable table) {
        return new TableResponse(table.getId(), table.getCode(), table.getSeats(), table.getZone(), table.getQrIssuedAt());
    }
}
