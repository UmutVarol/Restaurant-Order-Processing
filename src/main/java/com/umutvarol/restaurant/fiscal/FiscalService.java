package com.umutvarol.restaurant.fiscal;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.DomainException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.fiscal.dto.FiscalDocumentResponse;
import com.umutvarol.restaurant.fiscal.dto.InvoiceRequest;
import com.umutvarol.restaurant.fiscal.dto.ZReportResponse;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderLine;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.order.PaymentMethod;
import com.umutvarol.restaurant.payment.Bill;
import com.umutvarol.restaurant.payment.BillRepository;
import com.umutvarol.restaurant.payment.BillStatus;
import com.umutvarol.restaurant.payment.Payment;
import com.umutvarol.restaurant.payment.PaymentChangedEvent;
import com.umutvarol.restaurant.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issues the legal document of every paid bill: an ÖKC receipt for cash and card, an e-Arşiv invoice when part was
 * paid online or the guest asked for one. Each bill gets one document at most (the database enforces it); the call
 * to the device or integrator carries an idempotency key and runs outside any transaction, and a failure is retried
 * with growing pauses while the cashier screen shows it as not issued.
 */
@Service
public class FiscalService {

    private static final Logger audit = LoggerFactory.getLogger("fiscal.audit");
    private static final List<FiscalDocument.Status> RETRIED = List.of(FiscalDocument.Status.PENDING, FiscalDocument.Status.FAILED);

    private final FiscalDocumentRepository documents;
    private final BillRepository bills;
    private final FiscalDevices devices;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate tx;

    FiscalService(FiscalDocumentRepository documents, BillRepository bills, FiscalDevices devices,
                  ApplicationEventPublisher events, Clock clock, PlatformTransactionManager transactions) {
        this.documents = documents;
        this.bills = bills;
        this.devices = devices;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------------------------------- issuing

    /** A bill was paid and closed: its document is due. */
    @TransactionalEventListener
    void onBillChanged(PaymentChangedEvent event) {
        if (event.kind() == PaymentChangedEvent.Kind.BILL && event.billStatus() == BillStatus.CLOSED) {
            issueFor(event.billId());
        }
    }

    private void issueFor(Long billId) {
        Long id;
        try {
            id = tx.execute(status -> {
                Bill bill = bills.findById(billId).orElseThrow();
                Optional<FiscalDocument> existing = documents.findByBillId(billId);
                if (existing.isPresent() && existing.get().getStatus() != FiscalDocument.Status.WAITING) {
                    return null; // already issued or on its way
                }
                FiscalKind kind = existing.map(FiscalDocument::getKind).orElseGet(() -> kindFor(bill));
                if (devices.device(kind).isEmpty()) {
                    return null;
                }
                FiscalDocument document = existing.orElseGet(() -> FiscalDocument.forBill(billId, kind, clock.instant()));
                document.due(bill.total(), taxOf(bill), clock.instant());
                return documents.saveAndFlush(document).getId();
            });
        } catch (DataIntegrityViolationException raced) {
            return; // another thread created it, and issues it
        }
        if (id != null) {
            attempt(id);
        }
    }

    /** One try: the request is built in a transaction, the device is called outside one, the outcome saved in another. */
    private void attempt(Long id) {
        record Prepared(FiscalDevice device, FiscalDevice.Request request) {
        }
        Prepared prepared = tx.execute(status -> {
            FiscalDocument document = documents.findById(id).orElseThrow();
            if (!RETRIED.contains(document.getStatus())) {
                return null;
            }
            Optional<FiscalDevice> device = devices.device(document.getKind());
            return device.map(d -> new Prepared(d, request(document, bills.findById(document.getBillId()).orElseThrow()))).orElse(null);
        });
        if (prepared == null) {
            return;
        }
        FiscalDevice.Issued issued = null;
        String error = null;
        try {
            issued = prepared.device().issue(prepared.request());
        } catch (DomainException ex) {
            error = ex.getMessage();
        }
        FiscalDevice.Issued outcome = issued;
        String failure = error;
        tx.executeWithoutResult(status -> {
            FiscalDocument document = documents.findById(id).orElseThrow();
            if (outcome != null) {
                document.issued(prepared.device().provider(), outcome.reference(), outcome.number(), outcome.pdfUrl(), clock.instant());
                audit.info("fiscal.issued id={} bill={} kind={} number={} provider={}", id, document.getBillId(),
                        document.getKind(), outcome.number(), prepared.device().provider());
            } else {
                document.failed(failure, clock.instant());
                audit.warn("fiscal.failed id={} bill={} kind={} attempt={} error={}", id, document.getBillId(),
                        document.getKind(), document.getAttempts(), failure);
            }
            events.publishEvent(new FiscalDocumentChangedEvent(id, document.getBillId(), document.getStatus(), clock.instant()));
        });
    }

    /** Documents whose pause is over are tried again, oldest first. */
    @Scheduled(fixedDelayString = "${restaurant.fiscal.retry-interval:60s}", initialDelayString = "${restaurant.fiscal.retry-interval:60s}")
    void retryDue() {
        tx.execute(status -> documents.findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(RETRIED, clock.instant()))
                .forEach(document -> attempt(document.getId()));
    }

    // ------------------------------------------------------------------------------------------- at the till

    /**
     * A guest wants an e-Arşiv invoice. While the bill is open it is noted and issued on payment; a paid bill whose
     * receipt was not issued yet gets the invoice instead.
     *
     * @throws InvalidStateException {@code FISCAL_ALREADY_ISSUED} once a document is issued or on its way: a bill
     *                               gets one legal document only
     */
    public FiscalDocumentResponse requestInvoice(Long billId, InvoiceRequest request) {
        String taxId = TaxIds.requireValid(request.taxId());
        if (devices.device(FiscalKind.E_ARSIV).isEmpty()) {
            throw new BusinessRuleException("FISCAL_NOT_AVAILABLE", "e-Arşiv invoices are not set up");
        }
        String name = request.name().strip();
        String address = request.address() == null || request.address().isBlank() ? null : request.address().strip();
        Long id = tx.execute(status -> {
            Bill bill = bills.findById(billId).orElseThrow(() -> new ResourceNotFoundException("bill", billId));
            Optional<FiscalDocument> existing = documents.findByBillId(billId);
            if (existing.isPresent() && existing.get().getStatus() != FiscalDocument.Status.WAITING) {
                throw new InvalidStateException("FISCAL_ALREADY_ISSUED", "This bill already has its legal document",
                        Map.of("billId", billId));
            }
            FiscalDocument document = existing.orElseGet(() -> FiscalDocument.invoiceRequested(billId, name, taxId, address, clock.instant()));
            document.buyer(name, taxId, address);
            if (bill.getStatus() == BillStatus.CLOSED) {
                document.due(bill.total(), taxOf(bill), clock.instant());
            }
            return documents.saveAndFlush(document).getId();
        });
        audit.info("fiscal.invoice-requested id={} bill={}", id, billId);
        attempt(id);
        return tx.execute(status -> response(documents.findById(id).orElseThrow()));
    }

    /** Tries a document that failed again, now. */
    public FiscalDocumentResponse retry(Long id) {
        tx.executeWithoutResult(status -> {
            FiscalDocument document = documents.findById(id).orElseThrow(() -> new ResourceNotFoundException("fiscal_document", id));
            if (!RETRIED.contains(document.getStatus())) {
                throw new InvalidStateException("FISCAL_NOT_RETRYABLE", "Only a document that was not issued can be tried again",
                        Map.of("status", document.getStatus()));
            }
            document.retryNow(clock.instant());
        });
        attempt(id);
        return tx.execute(status -> response(documents.findById(id).orElseThrow()));
    }

    public Optional<FiscalDocumentResponse> forBill(Long billId) {
        return tx.execute(status -> documents.findByBillId(billId).map(this::response));
    }

    /** A day's documents, and any older one still not issued. */
    public List<FiscalDocumentResponse> documents(LocalDate day) {
        Instant from = day.atStartOfDay(clock.getZone()).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        return tx.execute(status -> Stream.concat(
                        documents.findByStatusInOrderByCreatedAtAsc(RETRIED).stream().filter(d -> d.getCreatedAt().isBefore(from)),
                        documents.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(from, to).stream())
                .map(this::response).toList());
    }

    /** Sofra's documents of the day against the ÖKC's own Z report. */
    public ZReportResponse zReport(LocalDate day) {
        Instant from = day.atStartOfDay(clock.getZone()).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        return tx.execute(status -> {
            List<FiscalDocument> receipts = documents.findByStatusAndKindAndIssuedAtGreaterThanEqualAndIssuedAtLessThan(
                    FiscalDocument.Status.ISSUED, FiscalKind.RECEIPT, from, to);
            List<FiscalDocument> invoices = documents.findByStatusAndKindAndIssuedAtGreaterThanEqualAndIssuedAtLessThan(
                    FiscalDocument.Status.ISSUED, FiscalKind.E_ARSIV, from, to);
            BigDecimal receiptTotal = sum(receipts);
            BigDecimal device = devices.device(FiscalKind.RECEIPT).flatMap(d -> d.dailyTotal(day)).orElse(null);
            int waiting = documents.findByStatusInOrderByCreatedAtAsc(RETRIED).size();
            return new ZReportResponse(day, receipts.size(), receiptTotal, invoices.size(), sum(invoices), device,
                    device == null ? null : receiptTotal.subtract(device), waiting);
        });
    }

    // ------------------------------------------------------------------------------------------- helpers

    /** Paid (partly) online, so there is no till receipt for it: an e-Arşiv invoice. Otherwise the ÖKC's receipt. */
    private static FiscalKind kindFor(Bill bill) {
        return succeeded(bill).anyMatch(p -> p.getMethod() == PaymentMethod.ONLINE) ? FiscalKind.E_ARSIV : FiscalKind.RECEIPT;
    }

    private static Stream<Payment> succeeded(Bill bill) {
        return bill.getPayments().stream().filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED);
    }

    private static BigDecimal taxOf(Bill bill) {
        return bill.getOrders().stream().filter(o -> o.getStatus() != OrderStatus.CANCELLED)
                .map(o -> o.getPricing().tax()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private FiscalDevice.Request request(FiscalDocument document, Bill bill) {
        List<FiscalDevice.Line> lines = new ArrayList<>();
        for (Order order : bill.getOrders()) {
            if (order.getStatus() == OrderStatus.CANCELLED) {
                continue;
            }
            for (OrderLine line : order.getLines()) {
                if (line.billableQuantity() > 0) {
                    lines.add(new FiscalDevice.Line(line.getItemName(), line.billableQuantity(), line.getUnitPrice(), line.getLineTotal()));
                }
            }
        }
        Map<PaymentMethod, BigDecimal> payments = new EnumMap<>(PaymentMethod.class);
        succeeded(bill).forEach(p -> payments.merge(p.getMethod(), p.getAmount().subtract(p.getRefundedAmount()), BigDecimal::add));
        FiscalDevice.Buyer buyer = document.getBuyerTaxId() == null ? null
                : new FiscalDevice.Buyer(document.getBuyerName(), document.getBuyerTaxId(), document.getBuyerAddress());
        return new FiscalDevice.Request("fiscal-" + document.getBillId(), document.getKind(), document.getBillId(),
                clock.instant(), lines, document.getTotal(), document.getTax(), payments, buyer);
    }

    private static BigDecimal sum(List<FiscalDocument> list) {
        return list.stream().map(FiscalDocument::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private FiscalDocumentResponse response(FiscalDocument d) {
        return new FiscalDocumentResponse(d.getId(), d.getBillId(), d.getKind(), d.getStatus(), d.getProvider(), d.getReference(),
                d.getDocumentNumber(), d.getPdfUrl(), d.getTotal(), d.getTax(), d.getBuyerName(), TaxIds.masked(d.getBuyerTaxId()),
                d.getAttempts(), d.getLastError(), d.getNextAttemptAt(), d.getCreatedAt(), d.getIssuedAt(),
                devices.simulated(d.getKind()));
    }
}
