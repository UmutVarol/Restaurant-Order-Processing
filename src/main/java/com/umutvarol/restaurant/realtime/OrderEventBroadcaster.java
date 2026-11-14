package com.umutvarol.restaurant.realtime;

import com.umutvarol.restaurant.business.BranchContext;
import com.umutvarol.restaurant.business.BranchScoped;
import com.umutvarol.restaurant.common.error.TooManyRequestsException;
import com.umutvarol.restaurant.fiscal.FiscalDocumentChangedEvent;
import com.umutvarol.restaurant.guest.GuestRequestChangedEvent;
import com.umutvarol.restaurant.floor.TableChangedEvent;
import com.umutvarol.restaurant.reservation.ReservationChangedEvent;
import com.umutvarol.restaurant.inventory.StockChangedEvent;
import com.umutvarol.restaurant.order.OrderChangedEvent;
import com.umutvarol.restaurant.printing.PrintJobChangedEvent;
import com.umutvarol.restaurant.payment.PaymentChangedEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes order and payment changes to every connected screen (kitchen display, floor plan, order list) over
 * Server-Sent Events. Events are only broadcast after the originating transaction commits.
 */
@Component
public class OrderEventBroadcaster {

    static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);
    private static final Logger log = LoggerFactory.getLogger(OrderEventBroadcaster.class);

    private static final Duration RETRY_AFTER_LIMIT = Duration.ofSeconds(30);

    /**
     * Who holds a stream: an event goes only to streams of its own business (docs/adr/0001-multi-business.md), and
     * of its own branch unless the stream watches all of them.
     */
    private record Subscriber(String user, long business, @Nullable Long branch) {
    }

    /** Open streams and who each belongs to. */
    private final Map<SseEmitter, Subscriber> emitters = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final RealtimeProperties limits;

    public OrderEventBroadcaster(RealtimeProperties limits) {
        this.limits = limits;
    }

    /**
     * Opens a stream for the given user.
     *
     * @throws TooManyRequestsException if the user, or the server as a whole, already has the maximum number of streams
     */
    public SseEmitter subscribe(String user) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
        synchronized (emitters) {
            if (emitters.size() >= limits.maxSubscribers()) {
                throw new TooManyRequestsException("TOO_MANY_STREAMS",
                        "The server has reached its limit of live connections. Try again shortly.", RETRY_AFTER_LIMIT);
            }
            Subscriber subscriber = new Subscriber(user, currentBusiness(), BranchContext.current());
            long mine = emitters.values().stream()
                    .filter(other -> other.user().equals(user) && other.business() == subscriber.business()).count();
            if (mine >= limits.maxSubscribersPerUser()) {
                throw new TooManyRequestsException("TOO_MANY_STREAMS",
                        "Too many live connections for this account. Close another screen or tab.", RETRY_AFTER_LIMIT);
            }
            emitters.put(emitter, subscriber);
        }
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(error -> emitters.remove(emitter));
        send(emitter, SseEmitter.event().name("connected").data("{\"subscribers\":" + emitters.size() + "}",
                MediaType.APPLICATION_JSON).reconnectTime(3_000));
        return emitter;
    }

    @TransactionalEventListener
    public void onOrderChanged(OrderChangedEvent event) {
        String id = Long.toString(sequence.incrementAndGet());
        broadcast(event, () -> SseEmitter.event().id(id).name("order").data(event, MediaType.APPLICATION_JSON));
    }

    @TransactionalEventListener
    public void onGuestRequest(GuestRequestChangedEvent event) {
        String id = Long.toString(sequence.incrementAndGet());
        broadcast(event, () -> SseEmitter.event().id(id).name("guest").data(event, MediaType.APPLICATION_JSON));
    }

    @TransactionalEventListener
    public void onPrintJob(PrintJobChangedEvent event) {
        String id = Long.toString(sequence.incrementAndGet());
        broadcast(event, () -> SseEmitter.event().id(id).name("print").data(event, MediaType.APPLICATION_JSON));
    }

    @TransactionalEventListener
    public void onPaymentChanged(PaymentChangedEvent event) {
        String id = Long.toString(sequence.incrementAndGet());
        broadcast(event, () -> SseEmitter.event().id(id).name("payment").data(event, MediaType.APPLICATION_JSON));
    }

    @TransactionalEventListener
    public void onFiscalDocument(FiscalDocumentChangedEvent event) {
        String id = Long.toString(sequence.incrementAndGet());
        broadcast(event, () -> SseEmitter.event().id(id).name("fiscal").data(event, MediaType.APPLICATION_JSON));
    }

    @TransactionalEventListener
    public void onTableChanged(TableChangedEvent event) {
        String id = Long.toString(sequence.incrementAndGet());
        broadcast(event, () -> SseEmitter.event().id(id).name("table").data(event, MediaType.APPLICATION_JSON));
    }

    @TransactionalEventListener
    public void onReservationChanged(ReservationChangedEvent event) {
        String id = Long.toString(sequence.incrementAndGet());
        broadcast(event, () -> SseEmitter.event().id(id).name("reservation").data(event, MediaType.APPLICATION_JSON));
    }

    @TransactionalEventListener
    public void onStockChanged(StockChangedEvent event) {
        String id = Long.toString(sequence.incrementAndGet());
        broadcast(event, () -> SseEmitter.event().id(id).name("stock").data(event, MediaType.APPLICATION_JSON));
    }

    /** Keeps idle connections alive through proxies and lets us detect clients that went away. */
    @Scheduled(fixedRate = 20_000, initialDelay = 20_000)
    void heartbeat() {
        broadcastToAll(() -> SseEmitter.event().comment("heartbeat"));
    }

    public int subscriberCount() {
        return emitters.size();
    }

    /**
     * Ends every stream when the application shuts down. Streams are long-lived requests, so otherwise graceful
     * shutdown would wait out its whole grace period for them; clients reconnect to the next instance on their own.
     */
    @EventListener(ContextClosedEvent.class)
    void closeAll() {
        for (SseEmitter emitter : emitters.keySet()) {
            emitters.remove(emitter);
            emitter.complete();
        }
    }

    /**
     * To the screens of the business the event happened in, and of the branch it happened in (screens watching all
     * branches get every branch's). Builders are single-use, so a fresh one is created for every subscriber.
     */
    private void broadcast(Object happened, Supplier<SseEmitter.SseEventBuilder> event) {
        long business = currentBusiness();
        Long branch = happened instanceof BranchScoped scoped ? scoped.branchId() : null;
        emitters.forEach((emitter, subscriber) -> {
            if (subscriber.business() == business
                    && (branch == null || subscriber.branch() == null || branch.equals(subscriber.branch()))) {
                send(emitter, event.get());
            }
        });
    }

    private void broadcastToAll(Supplier<SseEmitter.SseEventBuilder> event) {
        for (SseEmitter emitter : emitters.keySet()) {
            send(emitter, event.get());
        }
    }

    private static long currentBusiness() {
        Long business = com.umutvarol.restaurant.business.BusinessContext.current();
        return business == null ? 1L : business;
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException ex) {
            log.debug("Dropping disconnected SSE subscriber: {}", ex.getMessage());
            emitters.remove(emitter);
            emitter.completeWithError(ex);
        }
    }
}
