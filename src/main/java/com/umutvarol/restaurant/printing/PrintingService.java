package com.umutvarol.restaurant.printing;

import com.umutvarol.restaurant.common.config.RestaurantProperties;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.menu.StationService;
import com.umutvarol.restaurant.order.CourseFiredEvent;
import com.umutvarol.restaurant.order.LineVoidedEvent;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
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
    private final StationService stations;
    private final PrintJobRepository jobs;
    private final OrderRepository orders;
    private final PrinterClient client;
    private final PrintingProperties properties;
    private final RestaurantProperties restaurant;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final Clock clock;
    /** Sofra Cloud: the server never connects to an address a business gave it; every printer prints via a bridge. */
    private final boolean cloud;

    public PrintingService(PrinterRepository printers, StationService stations, PrintJobRepository jobs, OrderRepository orders,
                           PrinterClient client, PrintingProperties properties, RestaurantProperties restaurant,
                           ApplicationEventPublisher events, PlatformTransactionManager transactionManager, Clock clock,
                           @org.springframework.beans.factory.annotation.Value("${restaurant.cloud.enabled:false}") boolean cloud) {
        this.cloud = cloud;
        this.printers = printers;
        this.stations = stations;
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
        boolean viaBridge = cloud || Boolean.TRUE.equals(request.viaBridge());
        if (viaBridge) {
            // the address is on the restaurant's network, which only the bridge can see: never looked up here
            PrinterAddressPolicy.checkForm(host, request.port(), properties);
        } else {
            PrinterAddressPolicy.resolve(host, request.port(), properties);
        }
        stations.requireExisting(request.stations());
        printer.update(name, host, request.port(), Integer.parseInt(request.paperWidth()), request.stations(),
                request.enabled(), viaBridge);
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
                : ticketFor(printer, order(orderId), KitchenTicket.Mode.TICKET, 0);
        return new TicketPreview(printer.getName(), printer.columns(), ticket.text(printer.columns()));
    }

    // ------------------------------------------------------------------------------------------- tickets

    /** Queues the new order's tickets once it is safely committed: the dishes sent to the kitchen now. */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderChanged(OrderChangedEvent event) {
        if (event.kind() == OrderChangedEvent.Kind.CREATED) {
            queue(order(event.orderId()), PrintJob.Kind.TICKET, null, OrderLine::isFired, job -> job);
        }
    }

    /** A held course was fired: its stations print it. */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCourseFired(CourseFiredEvent event) {
        queue(order(event.orderId()), PrintJob.Kind.FIRE, null, line -> line.getCourse() == event.course(),
                job -> job.forCourse(event.course()));
    }

    /** Dishes being made were voided: their station gets a slip. */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onLineVoided(LineVoidedEvent event) {
        queue(order(event.orderId()), PrintJob.Kind.VOID, null, line -> line.getId().equals(event.lineId()),
                job -> job.forVoid(event.lineId(), event.units()));
    }

    /** Prints an order's tickets again, e.g. after the kitchen printer jammed. */
    public List<PrintJobResponse> reprint(Long orderId, String actor) {
        List<PrintJob> queued = queue(order(orderId), PrintJob.Kind.REPRINT, actor, OrderLine::isFired, job -> job);
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

    private List<PrintJob> queue(Order order, PrintJob.Kind kind, @Nullable String actor, Predicate<OrderLine> which,
                                 UnaryOperator<PrintJob> details) {
        List<PrintJob> queued = new ArrayList<>();
        Instant now = clock.instant();
        for (Printer printer : printers.findByEnabledTrue()) {
            boolean hasLines = order.getLines().stream().filter(which)
                    .anyMatch(line -> (kind == PrintJob.Kind.VOID || line.activeQuantity() > 0) && printer.prints(line.getStationCode()));
            if (hasLines) {
                queued.add(jobs.save(details.apply(new PrintJob(printer, kind, order.getId(), actor, now))));
            }
        }
        return queued;
    }

    // ------------------------------------------------------------------------------------------- the worker

    @Transactional(propagation = Propagation.NEVER)
    public void printDue() {
        if (!properties.enabled()) {
            return;
        }
        if (cloud) {
            return;
        }
        // jobs for printers behind a bridge wait for the bridge
        List<Long> due = transaction.execute(status -> jobs.findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                PrintJob.Status.QUEUED, clock.instant(), Limit.of(20)).stream()
                .filter(job -> !job.getPrinter().isViaBridge())
                .map(PrintJob::getId).toList());
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

    // ------------------------------------------------------------------------------------------- print bridges

    /** A ticket for a print bridge: where to send it on the restaurant's network, and the printer's bytes. */
    public record BridgeJob(long id, String host, int port, String bytes) {
    }

    /** How long a bridge has to report back before the job is offered again. */
    static final Duration BRIDGE_LEASE = Duration.ofSeconds(60);

    /**
     * Hands a branch's bridge the tickets due on its printers, rendered, and leases them so they are handed out once.
     * The printer's own address is sent along: the bridge decides there whether it may connect to it.
     */
    public List<BridgeJob> claimForBridge(long branchId, int max) {
        Instant now = clock.instant();
        List<Long> due = transaction.execute(status -> jobs.findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                PrintJob.Status.QUEUED, now, Limit.of(100)).stream()
                .filter(job -> (cloud || job.getPrinter().isViaBridge()) && job.getPrinter().isEnabled()
                        && Long.valueOf(branchId).equals(job.getPrinter().getBranchId()))
                .limit(Math.max(1, Math.min(max, 20)))
                .map(PrintJob::getId).toList());
        List<BridgeJob> claimed = new java.util.ArrayList<>();
        for (Long id : due == null ? List.<Long>of() : due) {
            BridgeJob job = transaction.execute(status -> {
                Prepared prepared = prepare(id);
                if (prepared == null) {
                    return null;
                }
                jobs.findById(id).orElseThrow().lease(now.plus(BRIDGE_LEASE));
                return new BridgeJob(id, prepared.host(), prepared.port(), java.util.Base64.getEncoder().encodeToString(prepared.bytes()));
            });
            if (job != null) {
                claimed.add(job);
            }
        }
        return claimed;
    }

    /** The bridge printed the job, or says why it could not; another branch's job is not its to report. */
    public void bridgeResult(long branchId, long jobId, @Nullable String error) {
        boolean ours = Boolean.TRUE.equals(transaction.execute(status -> jobs.findById(jobId)
                .filter(job -> job.getStatus() == PrintJob.Status.QUEUED)
                .filter(job -> Long.valueOf(branchId).equals(job.getPrinter().getBranchId()))
                .isPresent()));
        if (!ours) {
            throw new com.umutvarol.restaurant.common.error.ResourceNotFoundException("print_job", jobId);
        }
        String failure = error == null || error.isBlank() ? null : error.strip();
        transaction.executeWithoutResult(status -> record(jobId, failure));
    }

    private @Nullable Prepared prepare(Long jobId) {
        PrintJob job = jobs.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != PrintJob.Status.QUEUED) {
            return null;
        }
        Printer printer = job.getPrinter();
        KitchenTicket ticket = switch (job.getKind()) {
            case TEST -> KitchenTicket.test(printer, properties.language(), clock.instant(), restaurant.timeZone());
            case VOID -> {
                Order order = order(job.getOrderId());
                OrderLine line = order.getLines().stream().filter(l -> l.getId().equals(job.getLineId())).findFirst().orElseThrow();
                yield KitchenTicket.voidSlip(printer, order, line, job.getUnits(), properties.language(), restaurant.timeZone());
            }
            case FIRE -> ticketFor(printer, order(job.getOrderId()), KitchenTicket.Mode.FIRE, job.getCourse());
            case REPRINT -> ticketFor(printer, order(job.getOrderId()), KitchenTicket.Mode.REPRINT, 0);
            case TICKET -> ticketFor(printer, order(job.getOrderId()), KitchenTicket.Mode.TICKET, 0);
        };
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
                job.getOrderId(), job.getStatus(), error, at, job.getBranchId()));
    }

    private KitchenTicket ticketFor(Printer printer, Order order, KitchenTicket.Mode mode, int course) {
        List<OrderLine> lines = order.getLines().stream()
                .filter(line -> printer.prints(line.getStationCode()))
                .filter(line -> mode == KitchenTicket.Mode.FIRE ? line.getCourse() == course : line.isFired())
                .toList();
        return KitchenTicket.forOrder(printer, order, lines, mode, course, properties.language(), restaurant.timeZone());
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
