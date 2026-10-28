package com.umutvarol.restaurant.channel;

import com.umutvarol.restaurant.channel.dto.ChannelOrderResponse;
import com.umutvarol.restaurant.channel.dto.ChannelResponse;
import com.umutvarol.restaurant.channel.dto.MappingRequest;
import com.umutvarol.restaurant.channel.dto.MappingResponse;
import com.umutvarol.restaurant.channel.dto.SimulateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Delivery platforms", description = "Connections, item mapping and platform orders (managers)")
@RestController
@RequestMapping("/api/v1/channels")
class ChannelController {

    private final ChannelService channels;

    ChannelController(ChannelService channels) {
        this.channels = channels;
    }

    @Operation(summary = "Platforms and how each is connected")
    @GetMapping
    List<ChannelResponse> channels() {
        return channels.channels();
    }

    @Operation(summary = "Latest platform orders, with those waiting for a manager")
    @GetMapping("/orders")
    List<ChannelOrderResponse> orders() {
        return channels.recentOrders();
    }

    @Operation(summary = "Try a waiting platform order again", description = "After mapping its items or restocking a dish.")
    @PostMapping("/orders/{id}/retry")
    ChannelOrderResponse retry(@PathVariable Long id) {
        return channels.retry(id);
    }

    @Operation(summary = "Turn down a waiting platform order", description = "The platform is told.")
    @PostMapping("/orders/{id}/reject")
    ChannelOrderResponse reject(@PathVariable Long id) {
        return channels.reject(id);
    }

    @Operation(summary = "A platform's item mappings")
    @GetMapping("/{channel}/mappings")
    List<MappingResponse> mappings(@PathVariable String channel) {
        return channels.mappings(Channel.fromPath(channel));
    }

    @Operation(summary = "Map a platform item to one of our dishes")
    @PutMapping("/{channel}/mappings/{externalId}")
    MappingResponse map(@PathVariable String channel, @PathVariable String externalId, @Valid @RequestBody MappingRequest request) {
        return channels.map(Channel.fromPath(channel), externalId, request);
    }

    @Operation(summary = "Remove a mapping")
    @DeleteMapping("/{channel}/mappings/{externalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unmap(@PathVariable String channel, @PathVariable String externalId) {
        channels.unmap(Channel.fromPath(channel), externalId);
    }

    @Operation(summary = "Demo: make up an order from a simulated platform")
    @PostMapping("/{channel}/simulate")
    @ResponseStatus(HttpStatus.CREATED)
    ChannelOrderResponse simulate(@PathVariable String channel, @RequestBody(required = false) SimulateRequest request) {
        return channels.simulate(Channel.fromPath(channel), request != null && Boolean.TRUE.equals(request.withUnmappedItem()));
    }
}
