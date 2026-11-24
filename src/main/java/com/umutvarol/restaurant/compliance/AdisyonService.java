package com.umutvarol.restaurant.compliance;

import com.umutvarol.restaurant.common.error.DomainException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.fiscal.FiscalDocument;
import com.umutvarol.restaurant.fiscal.FiscalDocumentChangedEvent;
import com.umutvarol.restaurant.fiscal.FiscalService;
import com.umutvarol.restaurant.fiscal.dto.FiscalDocumentResponse;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderChangedEvent;
import com.umutvarol.restaurant.order.OrderLine;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.order.pricing.Vat;
import com.umutvarol.restaurant.order.pricing.VatLine;
import com.umutvarol.restaurant.payment.Bill;
import com.umutvarol.restaurant.payment.BillRepository;
import com.umutvarol.restaurant.payment.BillStatus;
import com.umutvarol.restaurant.payment.PaymentChangedEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the e-Adisyon of every table a business serves, when it keeps them at all (GİB notified it): opened with the
 * table's first order, updated as orders are added, voided or moved, closed when the bill is paid and its receipt or
 * invoice is issued. Each change is sent to the integrator outside any transaction, and retried until accepted.
 */
@Service
public class AdisyonService {

    private static final Logger audit = LoggerFactory.getLogger("adisyon.audit");
    private static final List<Adisyon.Delivery> DUE = List.of(Adisyon.Delivery.PENDING, Adisyon.Delivery.FAILED);

    /** What the manager sees: whether e-Adisyons are kept, and the day's. */
    public record View(boolean enabled, boolean simulated, long waiting, List<Entry> adisyons) {
    }

    public record Entry(Long id, Long billId, String ettn, @Nullable String tableCode, Adisyon.Status status,
                        BigDecimal total, BigDecimal tax, List<VatLine> vat, int itemCount, @Nullable String fiscalNumber,
                        Instant openedAt, @Nullable Instant closedAt, int version, int sentVersion,
                        Adisyon.Delivery delivery, @Nullable String provider, @Nullable String documentNumber,
                        int attempts, @Nullable String lastError, @Nullable Instant nextAttemptAt) {
    }

    private final AdisyonRepository adisyons;
    private final BillRepository bills;
    private final AdisyonSenders senders;
    private final FiscalService fiscal;
    private final Clock clock;
    private final TransactionTemplate tx;

    AdisyonService(AdisyonRepository adisyons, BillRepository bills, AdisyonSenders senders, FiscalService fiscal,
                   Clock clock, PlatformTransactionManager transactions) {
        this.adisyons = adisyons;
        this.bills = bills;
        this.senders = senders;
        this.fiscal = fiscal;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------------------------------- following the table

    @TransactionalEventListener
    void onOrderChanged(OrderChangedEvent event) {
        if (event.orderType() == OrderType.DINE_IN) {
            follow(() -> bills.findByOrderId(event.orderId()));
        }
    }

    @TransactionalEventListener
    void onBillChanged(PaymentChangedEvent event) {
        if (event.kind() == PaymentChangedEvent.Kind.BILL) {
            follow(() -> bills.findById(event.billId()));
        }
    }

    /** The receipt or invoice was issued: its number closes the adisyon. */
    @TransactionalEventListener
    void onFiscalDocument(FiscalDocumentChangedEvent event) {
        if (event.status() == FiscalDocument.Status.ISSUED) {
            follow(() -> bills.findById(event.billId()));
        }
    }

    /** Runs after the till's change committed: whatever happens here, the till's request has succeeded. */
    private void follow(Supplier<Optional<Bill>> find) {
        try {
            if (senders.sender().isEmpty()) {
                return;
            }
            Long id;
            try {
                id = tx.execute(status -> find.get().map(this::follow).orElse(null));
            } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException raced) {
                return; // another thread is following the same bill, and sends what it found
            }
            if (id != null) {
                attempt(id);
            }
        } catch (RuntimeException unexpected) {
            audit.error("adisyon.follow failed; the next change or the retry job catches up", unexpected);
        }
    }

    /** @return the adisyon to send, when it is new or changed */
    private @Nullable Long follow(Bill bill) {
        if (bill.getTable() == null || bill.getBranchId() == null) {
            return null; // e-Adisyon is for table service
        }
        Optional<Adisyon> existing = adisyons.findByBillId(bill.getId());
        List<Order> active = bill.getOrders().stream().filter(order -> order.getStatus() != OrderStatus.CANCELLED).toList();
        int items = active.stream().flatMap(order -> order.getLines().stream()).mapToInt(OrderLine::billableQuantity).sum();
        if (existing.isEmpty() && items == 0) {
            return null;
        }
        Instant now = clock.instant();
        Adisyon.Status status = switch (bill.getStatus()) {
            case OPEN -> Adisyon.Status.OPEN;
            case CLOSED -> Adisyon.Status.CLOSED;
            case VOID -> Adisyon.Status.CANCELLED;
        };
        List<VatLine> vat = Vat.of(active);
        BigDecimal tax = vat.stream().map(VatLine::vat).reduce(BigDecimal.ZERO, BigDecimal::add);
        String fiscalNumber = bill.getStatus() == BillStatus.CLOSED
                ? fiscal.forBill(bill.getId()).filter(d -> d.status() == FiscalDocument.Status.ISSUED)
                        .map(FiscalDocumentResponse::documentNumber).orElse(null)
                : null;
        Adisyon adisyon = existing.orElseGet(() -> Adisyon.open(bill.getId(), bill.getBranchId(), bill.getTable().getCode(),
                bill.getOpenedAt(), now));
        boolean changed = adisyon.follow(status, bill.getTable().getCode(), bill.total(), tax, VatLine.encode(vat), items,
                fiscalNumber, status == Adisyon.Status.OPEN ? null : bill.getClosedAt(), now);
        if (existing.isEmpty() || changed) {
            return adisyons.saveAndFlush(adisyon).getId();
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------- sending

    /** One try: the version is read in a transaction, sent outside one, the outcome saved in another. */
    private void attempt(Long id) {
        record Prepared(AdisyonSender sender, AdisyonSender.Request request) {
        }
        Prepared prepared = tx.execute(status -> {
            Adisyon adisyon = adisyons.findById(id).orElseThrow();
            if (!DUE.contains(adisyon.getDelivery())) {
                return null;
            }
            return senders.sender().map(sender -> new Prepared(sender, request(adisyon))).orElse(null);
        });
        if (prepared == null) {
            return;
        }
        AdisyonSender.Accepted accepted = null;
        String error = null;
        try {
            accepted = prepared.sender().send(prepared.request());
        } catch (DomainException ex) {
            error = ex.getMessage();
        }
        AdisyonSender.Accepted outcome = accepted;
        String failure = error;
        try {
            tx.executeWithoutResult(status -> {
                Adisyon adisyon = adisyons.findById(id).orElseThrow();
                if (outcome != null) {
                    adisyon.sent(prepared.request().version(), prepared.sender().provider(), outcome.documentNumber(), clock.instant());
                    audit.info("adisyon.sent id={} bill={} version={} status={} number={}", id, adisyon.getBillId(),
                            prepared.request().version(), prepared.request().status(), outcome.documentNumber());
                } else {
                    adisyon.failed(failure == null ? "not accepted" : failure, clock.instant());
                    audit.warn("adisyon.failed id={} bill={} version={} attempts={} error=\"{}\"", id, adisyon.getBillId(),
                            prepared.request().version(), adisyon.getAttempts(), failure);
                }
            });
        } catch (ObjectOptimisticLockingFailureException changedMeanwhile) {
            // a newer version was made while this one travelled; it is sent next and carries everything
        }
    }

    private AdisyonSender.Request request(Adisyon adisyon) {
        Bill bill = bills.findById(adisyon.getBillId()).orElseThrow();
        List<AdisyonSender.Line> lines = new ArrayList<>();
        for (Order order : bill.getOrders()) {
            if (order.getStatus() == OrderStatus.CANCELLED) {
                continue;
            }
            for (OrderLine line : order.getLines()) {
                if (line.billableQuantity() > 0) {
                    lines.add(new AdisyonSender.Line(line.getItemName(), line.billableQuantity(), line.getUnitPrice(),
                            line.getLineTotal(), line.getVatRate()));
                }
            }
        }
        return new AdisyonSender.Request(adisyon.getEttn(), adisyon.getVersion(), adisyon.getStatus(), adisyon.getBillId(),
                adisyon.getTableCode(), adisyon.getOpenedAt(), adisyon.getClosedAt(), lines, adisyon.getTotal(),
                adisyon.getTax(), VatLine.decode(adisyon.getVatLines()), adisyon.getFiscalNumber());
    }

    /** Versions that were not accepted, when their pause is over. */
    void retryDue() {
        tx.execute(status -> adisyons.findByDeliveryInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(DUE, clock.instant()))
                .forEach(adisyon -> attempt(adisyon.getId()));
    }

    /** A manager sends one again now, after the automatic attempts ran out. */
    public Entry retry(Long id) {
        tx.executeWithoutResult(status -> adisyons.findById(id).orElseThrow(() -> new ResourceNotFoundException("adisyon", id))
                .retryNow(clock.instant()));
        attempt(id);
        return tx.execute(status -> entry(adisyons.findById(id).orElseThrow()));
    }

    // ------------------------------------------------------------------------------------------- reading

    /** A day's e-Adisyons, and any older one whose latest version was not accepted yet. */
    public View day(LocalDate day) {
        Optional<AdisyonSender> sender = senders.sender();
        return tx.execute(status -> {
            ZoneId zone = clock.getZone();
            Instant from = day.atStartOfDay(zone).toInstant();
            Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
            Map<Long, Adisyon> shown = new LinkedHashMap<>();
            adisyons.findByDeliveryIn(DUE).forEach(a -> shown.put(a.getId(), a));
            adisyons.findByOpenedAtGreaterThanEqualAndOpenedAtLessThanOrderByOpenedAtDesc(from, to).forEach(a -> shown.put(a.getId(), a));
            List<Entry> entries = shown.values().stream()
                    .sorted((a, b) -> b.getOpenedAt().compareTo(a.getOpenedAt()))
                    .map(AdisyonService::entry).toList();
            return new View(sender.isPresent(), sender.map(SimulatedAdisyonSender.class::isInstance).orElse(false),
                    adisyons.countByDeliveryIn(DUE), entries);
        });
    }

    private static Entry entry(Adisyon a) {
        return new Entry(a.getId(), a.getBillId(), a.getEttn(), a.getTableCode(), a.getStatus(), a.getTotal(), a.getTax(),
                VatLine.decode(a.getVatLines()), a.getItemCount(), a.getFiscalNumber(), a.getOpenedAt(), a.getClosedAt(),
                a.getVersion(), a.getSentVersion(), a.getDelivery(), a.getProvider(), a.getDocumentNumber(), a.getAttempts(),
                a.getLastError(), a.getNextAttemptAt());
    }
}
