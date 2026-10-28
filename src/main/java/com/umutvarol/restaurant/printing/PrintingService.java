package com.umutvarol.restaurant.printing;

import com.umutvarol.restaurant.common.config.RestaurantProperties;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderChangedEvent;
import com.umutvarol.restaurant.order.OrderLine;
import com.umutvarol.restaurant.order.OrderRepository;
import com.umutvarol.restaurant.printing.dto.PrintJobResponse;
import com.umutvarol.restaurant.printing.dto.PrinterRequest;
import com.umutvarol.restaurant.printing.dto.PrinterResponse;
import com.umutvarol.restaurant.printing.dto.TicketPreview;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends each new order's lines to the printers that make them: starters and mains to the kitchen, drinks to the
 * bar. Tickets are queued in the database once the order is committed and sent by a background worker, outside
 * any transaction, so a slow or switched-off printer never holds up the till; failures are retried with growing
 * pauses, then reported. Meant for one application instance (the queue is not shared between instances).
 */
@Service
@Transactional
public class PrintingService {

    private static final Logger log = LoggerFactory.getLogger(PrintingService.class);

    private final PrinterRepository printers;
    private final PrintJobRepository jobs;
    private final OrderRepository orders;
    private final PrinterClient client;
    private final PrintingProperties properties;
    private final RestaurantProperties restaurant;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PrintingService(PrinterRepository printers, PrintJobRepository jobs, OrderRepository orders,
                           PrinterClient client, PrintingProperties properties, RestaurantProperties restaurant,
                           ApplicationEventPublisher events, PlatformTransactionManager transactionManager, Clock clock) {
        this.printers = printers;
        this.jobs = jobs;
        this.orders = orders;
        this.client = client;
        this.properties = properties;
        this.restaurant = restaurant;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------- printers

    @Transactional(readOnly = true)
    public List<PrinterResponse> printers() {
        return printers.findAllByOrderByNameAsc().stream().map(PrinterResponse::from).toList();
    }

    public PrinterResponse save(@Nullable Long id, PrinterRequest request) {
        Printer printer = id == null ? new Printer(clock.instant()) : find(id);
        String name = request.name().strip();
        if (printers.existsByNameIgnoreCaseAndIdNot(name, id == null ? -1L : id)) {
            throw new BusinessRuleException("PRINTER_NAME_TAKEN", "A printer called %s already exists".formatted(name),
                    Map.of("name", name));
        }
        String host = request.host().strip();
        PrinterAddressPolicy.resolve(host, request.port(), properties);
        printer.update(name, host, request.port(), Integer.parseInt(request.paperWidth()), request.categories(),
                request.enabled());
        return PrinterResponse.from(printers.save(printer));
    }

    public void delete(Long id) {
        printers.delete(find(id));
    }

    public PrintJobResponse test(Long id, String actor) {
        return PrintJobResponse.from(jobs.save(new PrintJob(find(id), PrintJob.Kind.TEST, null, actor, clock.instant())));
    }

    @Transactional(readOnly = true)
    public TicketPreview preview(Long id, @Nullable Long orderId) {
        Printer printer = find(id);
        KitchenTicket ticket = orderId == null
                ? KitchenTicket.test(printer, properties.language(), clock.instant(), restaurant.timeZone())
                : ticketFor(printer, order(orderId), false);
        return new TicketPreview(printer.getName(), printer.columns(), ticket.text(printer.columns()));
    }

    // ------------------------------------------------------------------------------------------- tickets

    /** Queues the new order's tickets once it is safely committed. */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderChanged(OrderChangedEvent event) {
        if (event.kind() == OrderChangedEvent.Kind.CREATED) {
            queue(order(event.orderId()), PrintJob.Kind.TICKET, null);
        }
    }

    /** Prints an order's tickets again, e.g. after the kitchen printer jammed. */
    public List<PrintJobResponse> reprint(Long orderId, String actor) {
        List<PrintJob> queued = queue(order(orderId), PrintJob.Kind.REPRINT, actor);
        if (queued.isEmpty()) {
            throw new BusinessRuleException("NO_PRINTER_FOR_ORDER", "No printer is set up for this order's dishes");
        }
        return queued.stream().map(PrintJobResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<PrintJobResponse> recentJobs() {
        return jobs.findTop30ByOrderByIdDesc().stream().map(PrintJobResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<PrintJobResponse> jobsFor(Long orderId) {
        return jobs.findByOrderIdOrderByIdAsc(orderId).stream().map(PrintJobResponse::from).toList();
    }

    private List<PrintJob> queue(Order order, PrintJob.Kind kind, @Nullable String actor) {
        List<PrintJob> queued = new ArrayList<>();
        Instant now = clock.instant();
        for (Printer printer : printers.findByEnabledTrue()) {
            boolean hasLines = order.getLines().stream()
                    .anyMatch(line -> line.activeQuantity() > 0 && printer.prints(line.getCategory()));
            if (hasLines) {
                queued.add(jobs.save(new PrintJob(printer, kind, order.getId(), actor, now)));
            }
        }
        return queued;
    }

    // ------------------------------------------------------------------------------------------- the worker

    @Scheduled(fixedDelayString = "${restaurant.printing.poll-interval:PT2S}", initialDelayString = "PT5S")
    @Transactional(propagation = Propagation.NEVER)
    public void printDue() {
        if (!properties.enabled()) {
            return;
        }
        List<Long> due = transaction.execute(status -> jobs.findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                PrintJob.Status.QUEUED, clock.instant(), Limit.of(20)).stream().map(PrintJob::getId).toList());
        for (Long id : due == null ? List.<Long>of() : due) {
            print(id);
        }
    }

    private void print(Long jobId) {
        // read and render in one short transaction, talk to the printer outside it, record the outcome in another
        Prepared prepared = transaction.execute(status -> prepare(jobId));
        if (prepared == null) {
            return;
        }
        String error = null;
        try {
            InetAddress address = PrinterAddressPolicy.resolve(prepared.host(), prepared.port(), properties);
            client.send(address, prepared.port(), prepared.bytes());
        } catch (IOException | RuntimeException ex) {
            error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        }
        String failure = error;
        transaction.executeWithoutResult(status -> record(jobId, failure));
    }

    private @Nullable Prepared prepare(Long jobId) {
        PrintJob job = jobs.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != PrintJob.Status.QUEUED) {
            return null;
        }
        Printer printer = job.getPrinter();
        KitchenTicket ticket = job.getKind() == PrintJob.Kind.TEST || job.getOrderId() == null
                ? KitchenTicket.test(printer, properties.language(), clock.instant(), restaurant.timeZone())
                : ticketFor(printer, order(job.getOrderId()), job.getKind() == PrintJob.Kind.REPRINT);
        return new Prepared(printer.getHost(), printer.getPort(), ticket.escPos(printer.columns()));
    }

    private void record(Long jobId, @Nullable String error) {
        PrintJob job = jobs.findById(jobId).orElseThrow();
        Instant now = clock.instant();
        if (error == null) {
            job.printed(now);
            announce(job, null, now);
            return;
        }
        log.warn("Print job {} for {} failed (attempt {}): {}", jobId, job.getPrinter().getName(), job.getAttempts() + 1, error);
        if (job.failed(error, now, properties.maxAttempts())) {
            announce(job, error, now);
        }
    }

    private void announce(PrintJob job, @Nullable String error, Instant at) {
        events.publishEvent(new PrintJobChangedEvent(job.getId(), job.getPrinter().getId(), job.getPrinter().getName(),
                job.getOrderId(), job.getStatus(), error, at));
    }

    private KitchenTicket ticketFor(Printer printer, Order order, boolean reprint) {
        List<OrderLine> lines = order.getLines().stream().filter(line -> printer.prints(line.getCategory())).toList();
        return KitchenTicket.forOrder(printer, order, lines, reprint, properties.language(), restaurant.timeZone());
    }

    private Printer find(Long id) {
        return printers.findById(id).orElseThrow(() -> new ResourceNotFoundException("printer", id));
    }

    private Order order(Long id) {
        return orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("order", id));
    }

    private record Prepared(String host, int port, byte[] bytes) {
    }
}
