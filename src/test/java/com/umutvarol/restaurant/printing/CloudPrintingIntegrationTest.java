package com.umutvarol.restaurant.printing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.business.BusinessRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Sofra Cloud never connects to an address a business typed in (that would reach the cloud's own network): every
 * printer prints through the branch's bridge, and one business's bridge never sees another business's tickets.
 */
@SpringBootTest(properties = {"restaurant.cloud.enabled=true", "restaurant.cloud.signup-enabled=true",
        "restaurant.cloud.base-domain=sofra.test", "restaurant.demo.enabled=false",
        "restaurant.security.rate-limit.public-requests-per-minute=100000",
        "restaurant.cloud.signups-per-client-per-hour=1000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CloudPrintingIntegrationTest {

    private static final AtomicInteger NUMBERS = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Test
    void everyPrinterPrintsThroughItsOwnBusinesssBridge() throws Exception {
        String kebab = open("Yazıcı");
        String cafe = open("Yazıcısız");
        // an address on the server's own machine: accepted as an address, never connected to by the server
        long printer = id(mvc.perform(with(kebab, json(post("/api/v1/printers"), """
                        {"name": "Mutfak", "host": "127.0.0.1", "port": 9100, "paperWidth": "80",
                         "stations": ["HOT"], "enabled": true}""")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.viaBridge").value(true))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(with(kebab, post("/api/v1/printers/{id}/test", printer))).andExpect(status().isAccepted());

        String kebabBridge = JsonPath.read(mvc.perform(with(kebab, json(post("/api/v1/print-bridges"), """
                        {"name": "Kasa PC"}"""))).andReturn().getResponse().getContentAsString(), "$.token");
        String cafeBridge = JsonPath.read(mvc.perform(with(cafe, json(post("/api/v1/print-bridges"), """
                        {"name": "Kasa PC"}"""))).andReturn().getResponse().getContentAsString(), "$.token");

        String none = mvc.perform(post("/api/v1/bridge/jobs/claim").header("X-Bridge-Token", cafeBridge))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(none, "$")).isEmpty();
        String mine = mvc.perform(post("/api/v1/bridge/jobs/claim").header("X-Bridge-Token", kebabBridge))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(mine, "$")).hasSize(1);
        long job = id(mine, "$[0].id");
        // the café's bridge cannot report the kebab shop's ticket either
        mvc.perform(json(post("/api/v1/bridge/jobs/{id}/result", job).header("X-Bridge-Token", cafeBridge), """
                        {"ok": true}""")).andExpect(status().isNotFound());
        mvc.perform(with(kebab, get("/api/v1/print-jobs"))).andExpect(jsonPath("$[0].status").value("QUEUED"));
    }

    private String open(String name) throws Exception {
        String unique = name + " " + NUMBERS.incrementAndGet();
        String address = BusinessRegistry.suggestAddress(unique);
        String body = mvc.perform(json(post("/api/v1/public/signup"), """
                        {"businessName": "%s", "email": "owner@example.com", "ownerName": "Sahibi",
                         "username": "%s-owner", "password": "masa dört hazır 9"}""".formatted(unique, address)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.session.accessToken");
    }

    private static long id(String body, String path) {
        return ((Number) JsonPath.read(body, path)).longValue();
    }

    private static MockHttpServletRequestBuilder with(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
