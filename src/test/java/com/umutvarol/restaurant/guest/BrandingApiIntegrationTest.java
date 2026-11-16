package com.umutvarol.restaurant.guest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A restaurant shows its own colour and logo to its guests; only managers change them, and only images get in. */
class BrandingApiIntegrationTest extends ApiIntegrationTest {

    /** The smallest real PNG: a 1×1 transparent pixel. */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");

    @AfterEach
    void leaveSofrasLook() throws Exception {
        mvc.perform(json(as(ADMIN, put("/api/v1/branding")), "{\"accent\": null}")).andExpect(status().isOk());
        mvc.perform(as(ADMIN, delete("/api/v1/branding/logo"))).andExpect(status().isOk());
    }

    @Test
    void managersChooseTheColourAndGuestsSeeIt() throws Exception {
        mvc.perform(as(WAITER, get("/api/v1/branding"))).andExpect(status().isOk()).andExpect(jsonPath("$.accent").doesNotExist());
        mvc.perform(json(as(WAITER, put("/api/v1/branding")), "{\"accent\": \"#2f6f4f\"}")).andExpect(status().isForbidden());
        mvc.perform(json(as(ADMIN, put("/api/v1/branding")), "{\"accent\": \"green\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(as(ADMIN, put("/api/v1/branding")), "{\"accent\": \"#2F6F4F\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accent").value("#2f6f4f"))
                .andExpect(jsonPath("$.logoVersion").doesNotExist());
        mvc.perform(get("/api/v1/public/table/{token}", issueQr(newTable())))
                .andExpect(jsonPath("$.brand.accent").value("#2f6f4f"));
    }

    @Test
    void aLogoIsAPngOrWebpAndGuestsFetchItByTheirTable() throws Exception {
        String token = issueQr(newTable());
        mvc.perform(get("/api/v1/public/table/{token}/logo", token)).andExpect(status().isNotFound());
        mvc.perform(as(WAITER, put("/api/v1/branding/logo")).contentType("image/png").content(PNG)).andExpect(status().isForbidden());
        // a script dressed up as a PNG is refused by its first bytes
        mvc.perform(as(ADMIN, put("/api/v1/branding/logo")).contentType("image/png").content("<svg onload=alert(1)>".getBytes()))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(as(ADMIN, put("/api/v1/branding/logo")).contentType("image/svg+xml").content("<svg/>".getBytes()))
                .andExpect(status().isUnsupportedMediaType());
        byte[] tooBig = Arrays.copyOf(PNG, 61 * 1024);
        mvc.perform(as(ADMIN, put("/api/v1/branding/logo")).contentType("image/png").content(tooBig))
                .andExpect(status().is4xxClientError());

        mvc.perform(as(ADMIN, put("/api/v1/branding/logo")).contentType("image/png").content(PNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoVersion").isNumber());
        byte[] served = mvc.perform(get("/api/v1/public/table/{token}/logo", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(PNG);
        mvc.perform(get("/api/v1/public/table/{token}", token)).andExpect(jsonPath("$.brand.logoVersion").isNumber());
        // a made-up table learns nothing
        mvc.perform(get("/api/v1/public/table/{token}/logo", "x".repeat(40))).andExpect(status().isNotFound());

        mvc.perform(as(ADMIN, delete("/api/v1/branding/logo"))).andExpect(status().isOk()).andExpect(jsonPath("$.logoVersion").doesNotExist());
        mvc.perform(get("/api/v1/public/table/{token}/logo", token)).andExpect(status().isNotFound());
    }

    private String issueQr(String table) throws Exception {
        String body = mvc.perform(as(ADMIN, post("/api/v1/tables/{id}/qr", tableId(table))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    @Test
    void theFirstBytesDecideTheType() {
        assertThat(BrandingService.typeOf(PNG)).isEqualTo("image/png");
        assertThat(BrandingService.typeOf("RIFF\0\0\0\0WEBPVP8 ".getBytes())).isEqualTo("image/webp");
        assertThat(BrandingService.typeOf("GIF89a".getBytes())).isNull();
        assertThat(BrandingService.typeOf(new byte[0])).isNull();
    }
}
