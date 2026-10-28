package com.umutvarol.restaurant.fiscal;

import com.umutvarol.restaurant.common.error.UpstreamServiceException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks JSON to the ÖKC vendor's local bridge or to the e-Arşiv integrator: {@code POST {base}/documents} with the
 * sale and an {@code Idempotency-Key}, answered with {@code reference}, {@code number} and {@code pdfUrl};
 * {@code GET {base}/z-report?date=} for the device's own daily total. The vendor's or integrator's own API is mapped
 * onto this in their bridge or adapter, which their contract gives access to.
 */
class HttpFiscalDevice implements FiscalDevice {

    private final String provider;
    private final RestClient http;

    HttpFiscalDevice(String provider, URI baseUrl, String apiKey, RestClient.Builder builder) {
        this.provider = provider;
        this.http = builder.baseUrl(baseUrl.toString()).defaultHeader("X-Api-Key", apiKey).build();
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public Issued issue(Request request) {
        try {
            Issued issued = http.post().uri("/documents").contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", request.key()).body(request).retrieve().body(Issued.class);
            if (issued == null || issued.reference() == null || issued.number() == null) {
                throw new UpstreamServiceException("FISCAL_UNAVAILABLE", provider + " answered without a document number");
            }
            return issued;
        } catch (RestClientException ex) {
            throw new UpstreamServiceException("FISCAL_UNAVAILABLE", provider + " did not issue the document");
        }
    }

    @Override
    public Optional<BigDecimal> dailyTotal(LocalDate day) {
        try {
            Map<?, ?> report = http.get().uri("/z-report?date={day}", day).retrieve().body(Map.class);
            Object total = report == null ? null : report.get("total");
            return total == null ? Optional.empty() : Optional.of(new BigDecimal(total.toString()));
        } catch (RestClientException | NumberFormatException ex) {
            return Optional.empty();
        }
    }
}
