package com.umutvarol.restaurant.channel;

import com.umutvarol.restaurant.common.error.UpstreamServiceException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends updates to a platform's order API (or to the integrator that bridges to it), as JSON with a bearer token:
 * {@code POST {base}/orders/{id}/status {"status"}} and {@code PUT {base}/items/{id}/availability {"available"}}.
 * Each platform's own endpoint names are set up in that bridge when the partner contract gives access to them.
 */
class HttpChannelAdapter implements ChannelAdapter {

    private final RestClient http;

    HttpChannelAdapter(URI baseUrl, String token, RestClient.Builder builder) {
        this.http = builder.baseUrl(baseUrl.toString()).defaultHeader("Authorization", "Bearer " + token).build();
    }

    @Override
    public void updateStatus(Channel channel, String externalOrderId, ChannelStatus status) {
        call(() -> http.post().uri("/orders/{id}/status", externalOrderId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", status.name())).retrieve().toBodilessEntity());
    }

    @Override
    public void setAvailability(Channel channel, String externalItemId, boolean available) {
        call(() -> http.put().uri("/items/{id}/availability", externalItemId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("available", available)).retrieve().toBodilessEntity());
    }

    private static void call(Runnable request) {
        try {
            request.run();
        } catch (RestClientException ex) {
            throw new UpstreamServiceException("CHANNEL_UNAVAILABLE", "The platform did not accept the update");
        }
    }
}
