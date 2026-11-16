package com.umutvarol.restaurant.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Posts to the push service over HTTPS, with short timeouts: a slow push service must not hold anything up. */
@Component
class HttpPushTransport implements PushTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpPushTransport.class);

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Override
    public int post(URI endpoint, Map<String, String> headers, byte[] body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException ex) {
            log.debug("Push: {} could not be reached: {}", endpoint.getHost(), ex.getMessage());
            return 0;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }
}
