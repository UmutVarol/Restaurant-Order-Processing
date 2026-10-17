package com.umutvarol.restaurant.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.umutvarol.restaurant.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class OrderEventStreamIntegrationTest extends ApiIntegrationTest {

    @Test
    void screensAreNotifiedOfNewOrdersAndStatusChanges() throws Exception {
        MvcResult stream = mvc.perform(as(CHEF, get("/api/v1/events/orders").accept(MediaType.TEXT_EVENT_STREAM)))
                .andExpect(request().asyncStarted())
                .andReturn();

        long id = placeTakeaway("Streamed Sena", "Mantı", 1);
        mvc.perform(as(CHEF, post("/api/v1/orders/{id}/advance", id))).andExpect(status().isOk());

        String events = stream.getResponse().getContentAsString();
        assertThat(events)
                .contains("event:connected")
                .contains("event:order")
                .contains("\"kind\":\"CREATED\"")
                .contains("\"orderId\":" + id)
                .contains("\"customerName\":\"Streamed Sena\"")
                .contains("\"kind\":\"STATUS_CHANGED\",\"orderId\":" + id)
                .contains("\"status\":\"PREPARING\",\"previousStatus\":\"PENDING\"");
    }

    @Test
    void theStreamRequiresSigningIn() throws Exception {
        mvc.perform(get("/api/v1/events/orders")).andExpect(status().isUnauthorized());
    }
}
