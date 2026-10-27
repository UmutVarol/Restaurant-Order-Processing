package com.umutvarol.restaurant.realtime;

import com.umutvarol.restaurant.common.error.TooManyRequestsException;
import com.umutvarol.restaurant.order.OrderChangedEvent;
import com.umutvarol.restaurant.payment.PaymentChangedEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
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

    /** Open streams and the user each belongs to. */
    private final Map<SseEmitter, String> emitters = new ConcurrentHashMap<>();
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
            long mine = emitters.values().stream().filter(user::equals).count();
            if (mine >= limits.maxSubscribersPerUser()) {
                throw new TooManyRequestsException("TOO_MANY_STREAMS",
                        "Too many live connections for this account. Close another screen or tab.", RETRY_AFTER_LIMIT);
            }
            emitters.put(emitter, user);
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
        broadcast(() -> SseEmitter.event().id(id).name("order").data(event, MediaType.APPLICATION_JSON));
    }

    @TransactionalEventListener
    public void onPaymentChanged(PaymentChangedEvent event) {
        String id = Long.toString(sequence.incrementAndGet());
        broadcast(() -> SseEmitter.event().id(id).name("payment").data(event, MediaType.APPLICATION_JSON));
    }

    /** Keeps idle connections alive through proxies and lets us detect clients that went away. */
    @Scheduled(fixedRate = 20_000, initialDelay = 20_000)
    void heartbeat() {
        broadcast(() -> SseEmitter.event().comment("heartbeat"));
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

    /** Builders are single-use, so a fresh one is created for every subscriber. */
    private void broadcast(Supplier<SseEmitter.SseEventBuilder> event) {
        for (SseEmitter emitter : emitters.keySet()) {
            send(emitter, event.get());
        }
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
