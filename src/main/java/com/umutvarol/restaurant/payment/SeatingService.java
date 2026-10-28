package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderChangedEvent;
import com.umutvarol.restaurant.order.OrderRepository;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.payment.dto.BillResponse;
import com.umutvarol.restaurant.table.DiningTable;
import com.umutvarol.restaurant.table.DiningTableRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A seating is a table's open bill with its orders. Guests change tables (move) and groups join up (merge); both
 * keep the money rules of {@link Bill}: a seating only moves to a free table, and a bill that has taken money is
 * never merged into another.
 */
@Service
@Transactional
public class SeatingService {

    private static final Logger audit = LoggerFactory.getLogger("order.audit");

    private final BillRepository bills;
    private final OrderRepository orders;
    private final DiningTableRepository tables;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public SeatingService(BillRepository bills, OrderRepository orders, DiningTableRepository tables,
                          ApplicationEventPublisher events, Clock clock) {
        this.bills = bills;
        this.orders = orders;
        this.tables = tables;
        this.events = events;
        this.clock = clock;
    }

    /** Moves the seating at one table to a free table. */
    public BillResponse move(long fromTableId, long toTableId, String actor) {
        DiningTable from = table(fromTableId);
        DiningTable to = table(toTableId);
        requireDifferent(from, to);
        Bill bill = lockSeating(from);
        if (seating(to) != null || orders.existsByTableIdAndStatusIn(to.getId(), OrderStatus.activeStatuses())) {
            throw new BusinessRuleException("TABLE_OCCUPIED", "Table %s is taken".formatted(to.getCode()),
                    Map.of("table", to.getCode()));
        }
        Instant now = clock.instant();
        bill.moveTo(to, now, actor);
        audit.info("table.move bill={} from={} to={} by={}", bill.getId(), from.getCode(), to.getCode(), actor);
        announce(bill, now);
        return BillResponse.from(bill);
    }

    /** Puts every order of one table's seating onto another table's bill. */
    public BillResponse merge(long fromTableId, long intoTableId, String actor) {
        DiningTable from = table(fromTableId);
        DiningTable into = table(intoTableId);
        requireDifferent(from, into);
        Bill source = seating(from);
        Bill target = seating(into);
        if (source == null || target == null) {
            throw noSeating(source == null ? from : into);
        }
        // always lock in the same order, so two tills merging the same tables cannot deadlock
        boolean sourceFirst = source.getId() < target.getId();
        Bill first = bills.findForUpdate((sourceFirst ? source : target).getId()).orElseThrow();
        Bill second = bills.findForUpdate((sourceFirst ? target : source).getId()).orElseThrow();
        source = sourceFirst ? first : second;
        target = sourceFirst ? second : first;
        Instant now = clock.instant();
        target.absorb(source, now, actor);
        audit.info("table.merge bill={} into={} from={} to={} by={}", source.getId(), target.getId(), from.getCode(),
                into.getCode(), actor);
        announce(target, now);
        events.publishEvent(PaymentChangedEvent.bill(source, now));
        return BillResponse.from(target);
    }

    private void announce(Bill bill, Instant at) {
        bills.flush();
        for (Order order : bill.getOrders()) {
            events.publishEvent(OrderChangedEvent.updated(order));
        }
        events.publishEvent(PaymentChangedEvent.bill(bill, at));
    }

    private Bill lockSeating(DiningTable table) {
        Bill bill = seating(table);
        if (bill == null) {
            throw noSeating(table);
        }
        return bills.findForUpdate(bill.getId()).orElseThrow();
    }

    private Bill seating(DiningTable table) {
        return bills.findFirstByTableIdAndStatusOrderByOpenedAtDesc(table.getId(), BillStatus.OPEN)
                .filter(Bill::hasActiveOrders)
                .orElse(null);
    }

    private DiningTable table(long id) {
        return tables.findById(id).orElseThrow(() -> new ResourceNotFoundException("table", id));
    }

    private static void requireDifferent(DiningTable a, DiningTable b) {
        if (a.getId().equals(b.getId())) {
            throw new BusinessRuleException("SAME_TABLE", "Pick a different table");
        }
    }

    private static InvalidStateException noSeating(DiningTable table) {
        return new InvalidStateException("TABLE_NOT_SEATED", "Nobody is seated at %s".formatted(table.getCode()),
                Map.of("table", table.getCode()));
    }
}
