package com.umutvarol.restaurant.realtime;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "Realtime", description = "Live order updates over Server-Sent Events")
@RestController
@RequestMapping("/api/v1/events")
class EventStreamController {

    private final OrderEventBroadcaster broadcaster;

    EventStreamController(OrderEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Operation(summary = "Subscribe to order events",
            description = "text/event-stream of `order` events (created / status changed). Heartbeats every 20s.")
    @GetMapping(path = "/orders", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter orders() {
        return broadcaster.subscribe();
    }
}
