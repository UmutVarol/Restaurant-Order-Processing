package com.umutvarol.restaurant.realtime;

import com.umutvarol.restaurant.order.OrderChangedEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes order changes to every connected screen (kitchen display, floor plan, order list) over
 * Server-Sent Events. Events are only broadcast after the originating transaction commits.
 */
@Component
public class OrderEventBroadcaster {

    static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);
    private static final Logger log = LoggerFactory.getLogger(OrderEventBroadcaster.class);

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(error -> emitters.remove(emitter));
        emitters.add(emitter);
        send(emitter, SseEmitter.event().name("connected").data("{\"subscribers\":" + emitters.size() + "}",
                MediaType.APPLICATION_JSON).reconnectTime(3_000));
        return emitter;
    }

    @TransactionalEventListener
    public void onOrderChanged(OrderChangedEvent event) {
        String id = Long.toString(sequence.incrementAndGet());
        broadcast(() -> SseEmitter.event().id(id).name("order").data(event, MediaType.APPLICATION_JSON));
    }

    /** Keeps idle connections alive through proxies and lets us detect clients that went away. */
    @Scheduled(fixedRate = 20_000, initialDelay = 20_000)
    void heartbeat() {
        broadcast(() -> SseEmitter.event().comment("heartbeat"));
    }

    public int subscriberCount() {
        return emitters.size();
    }

    /** Builders are single-use, so a fresh one is created for every subscriber. */
    private void broadcast(Supplier<SseEmitter.SseEventBuilder> event) {
        for (SseEmitter emitter : emitters) {
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
