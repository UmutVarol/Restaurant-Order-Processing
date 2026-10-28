package com.umutvarol.restaurant.channel;

import com.umutvarol.restaurant.channel.dto.ChannelOrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Delivery platform webhooks", description = "Signed order deliveries from Yemeksepeti, Getir Yemek and Trendyol Go")
@RestController
class ChannelWebhookController {

    private final ChannelService channels;

    ChannelWebhookController(ChannelService channels) {
        this.channels = channels;
    }

    @Operation(summary = "Receive a platform order", description = """
            X-Sofra-Signature is the hex HMAC-SHA256 of "<X-Sofra-Timestamp>.<body>" under the platform's webhook \\
            secret; the timestamp (Unix seconds) must be within five minutes. Delivering the same order again returns \\
            what became of it the first time.""")
    @PostMapping("/api/v1/channels/{channel}/webhook")
    ChannelOrderResponse receive(@PathVariable String channel, @RequestBody byte[] body,
                                 @RequestHeader(name = WebhookSignature.TIMESTAMP_HEADER, required = false) String timestamp,
                                 @RequestHeader(name = WebhookSignature.SIGNATURE_HEADER, required = false) String signature,
                                 HttpServletRequest request) {
        return channels.receive(Channel.fromPath(channel), body, timestamp, signature, request.getRemoteAddr());
    }
}
