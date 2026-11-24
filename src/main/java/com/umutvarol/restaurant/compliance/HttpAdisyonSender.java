package com.umutvarol.restaurant.compliance;

import com.umutvarol.restaurant.common.error.UpstreamServiceException;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks JSON to the e-Adisyon integrator's adapter: {@code PUT {base}/adisyons/{ettn}} with the version and an
 * {@code Idempotency-Key}, answered with {@code documentNumber}. The integrator's own API (UBL documents signed for GİB)
 * is mapped onto this in its adapter, which its contract gives access to, as for e-Arşiv.
 */
class HttpAdisyonSender implements AdisyonSender {

    private final RestClient http;

    HttpAdisyonSender(URI baseUrl, String apiKey, RestClient.Builder builder) {
        this.http = builder.baseUrl(baseUrl.toString()).defaultHeader("X-Api-Key", apiKey).build();
    }

    @Override
    public String provider() {
        return "E_ADISYON";
    }

    @Override
    public Accepted send(Request request) {
        try {
            Accepted accepted = http.put().uri("/adisyons/{ettn}", request.ettn()).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", request.ettn() + ":" + request.version()).body(request)
                    .retrieve().body(Accepted.class);
            if (accepted == null || accepted.documentNumber() == null || accepted.documentNumber().isBlank()) {
                throw new UpstreamServiceException("E_ADISYON_UNAVAILABLE", "The e-Adisyon integrator answered without a number");
            }
            return accepted;
        } catch (RestClientException ex) {
            throw new UpstreamServiceException("E_ADISYON_UNAVAILABLE", "The e-Adisyon integrator did not accept the adisyon");
        }
    }
}
