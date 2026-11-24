package com.umutvarol.restaurant.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * What Turkish law asks of prices and documents: menu prices include VAT with each dish's own rate, the price list
 * at the door shows them in lira with the date they last changed (and exports them for the Ministry), no service,
 * cover or table charge can be put on the menu, and a business GİB notified keeps an e-Adisyon of every table.
 */
class ComplianceIntegrationTest extends ApiIntegrationTest {

    @Test
    void anAlcoholicDrinkCarriesTwentyPercentVatWithinItsPrice() throws Exception {
        String created = mvc.perform(as(ADMIN, json(post("/api/v1/menu"), """
                        {"name": "Rakı (tek) %s", "category": "DRINK", "price": 240, "vatRate": 20}""".formatted(suffix()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vatRate").value(20.00))
                .andExpect(jsonPath("$.priceChangedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        long raki = ((Number) JsonPath.read(created, "$.id")).longValue();
        try {
            // 240 with 20 % inside is 40 VAT; 2 ayran at 77 with 10 % inside is 14; the guest pays 394
            mvc.perform(as(WAITER, json(post("/api/v1/orders/quote"), """
                            {"type": "TAKEAWAY", "items": [{"menuItemId": %d, "quantity": 1}, {"menuItemId": %d, "quantity": 2}]}"""
                            .formatted(raki, menuItemId("Ayran")))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pricing.total").value(394.00))
                    .andExpect(jsonPath("$.pricing.tax").value(54.00))
                    .andExpect(jsonPath("$.pricing.vat[0].rate").value(10.00))
                    .andExpect(jsonPath("$.pricing.vat[0].vat").value(14.00))
                    .andExpect(jsonPath("$.pricing.vat[1].rate").value(20.00))
                    .andExpect(jsonPath("$.pricing.vat[1].vat").value(40.00));
            mvc.perform(as(ADMIN, json(post("/api/v1/menu"), """
                            {"name": "Bad rate %s", "category": "DRINK", "price": 10, "vatRate": 120}""".formatted(suffix()))))
                    .andExpect(status().isBadRequest());
        } finally {
            mvc.perform(as(ADMIN, delete("/api/v1/menu/{id}", raki)));
        }
    }

    @Test
    void thePriceListShowsLiraWithVatAndTheDayPricesLastChanged() throws Exception {
        mvc.perform(as(WAITER, get("/api/v1/price-list"))).andExpect(status().isForbidden());
        Instant before = Instant.now();
        String list = mvc.perform(as(ADMIN, get("/api/v1/price-list")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.name == 'Ayran')].price").value(Matchers.contains(77.00)))
                .andExpect(jsonPath("$.items[?(@.name == 'Ayran')].vatRate").value(Matchers.contains(10.00)))
                .andExpect(jsonPath("$.lastChangedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(list, "$.items")).hasSizeGreaterThan(10);

        // changing a price moves the date on the list
        long ayran = menuItemId("Ayran");
        Map<String, Object> dish = JsonPath.read(mvc.perform(get("/api/v1/menu/{id}", ayran)).andReturn().getResponse().getContentAsString(), "$");
        String body = """
                {"name": "Ayran", "category": "DRINK", "price": %s, "icon": "%s", "prepTimeMinutes": %s, "stationCode": "%s"}""";
        mvc.perform(as(ADMIN, json(put("/api/v1/menu/{id}", ayran), body.formatted("79", dish.get("icon"), dish.get("prepTimeMinutes"), dish.get("stationCode")))))
                .andExpect(status().isOk());
        try {
            String after = mvc.perform(as(ADMIN, get("/api/v1/price-list"))).andReturn().getResponse().getContentAsString();
            assertThat(Instant.parse(JsonPath.read(after, "$.lastChangedAt"))).isAfterOrEqualTo(before.minusSeconds(1));
        } finally {
            mvc.perform(as(ADMIN, json(put("/api/v1/menu/{id}", ayran), body.formatted("77", dish.get("icon"), dish.get("prepTimeMinutes"), dish.get("stationCode")))))
                    .andExpect(status().isOk());
        }

        // the files for the Ministry's electronic price list
        mvc.perform(as(ADMIN, get("/api/v1/price-list/export?format=csv")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", Matchers.containsString("fiyat-listesi-")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(Matchers.containsString("Ürün;Fiyat (TL, KDV dahil);KDV oranı (%)")))
                .andExpect(content().string(Matchers.containsString(";İçecek;Ayran;77,00;10;Evet;")));
        mvc.perform(as(ADMIN, get("/api/v1/price-list/export?format=json")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema").value("sofra.price-list/1"))
                .andExpect(jsonPath("$.currency").value("TRY"))
                .andExpect(jsonPath("$.vatIncluded").value(true))
                .andExpect(jsonPath("$.items[?(@.name == 'Ayran')].price").value(Matchers.contains(77.00)));
    }

    @Test
    void aServiceCoverOrTableChargeCannotBePutOnTheMenu() throws Exception {
        for (String name : List.of("Servis Ücreti", "SERVİS ÜCRETİ %10", "Kuver", "Masa ucreti", "Cover charge")) {
            mvc.perform(as(ADMIN, json(post("/api/v1/menu"), """
                            {"name": "%s", "category": "STARTER", "price": 50}""".formatted(name))))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("MENU_CHARGE_NOT_ALLOWED"));
        }
        mvc.perform(as(ADMIN, json(post("/api/v1/menu"), """
                        {"name": "Meze tabağı %s", "category": "STARTER", "price": 50,
                         "optionGroups": [{"name": "Ek", "minSelect": 0, "maxSelect": 1, "choices": [{"name": "Kuver", "priceDelta": 20}]}]}"""
                        .formatted(suffix()))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MENU_CHARGE_NOT_ALLOWED"));
    }

    @Test
    void aTableKeepsAnEAdisyonFromItsFirstOrderToItsReceipt() throws Exception {
        String table = newTable();
        String order = mvc.perform(as(WAITER, json(post("/api/v1/orders"), """
                        {"type": "DINE_IN", "customerName": "Adisyon", "tableId": %d, "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 2}]}""".formatted(tableId(table), menuItemId("Ayran")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long orderId = ((Number) JsonPath.read(order, "$.id")).longValue();
        long billId = billIdOf(orderId);

        String open = mvc.perform(as(ADMIN, get("/api/v1/adisyon"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.simulated").value(true))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> opened = entry(open, billId);
        assertThat(opened.get("status")).isEqualTo("OPEN");
        assertThat(opened.get("tableCode")).isEqualTo(table);
        assertThat(opened.get("delivery")).isEqualTo("SENT");
        assertThat(((Number) opened.get("total")).doubleValue()).isEqualTo(154.0);
        assertThat(((Number) opened.get("itemCount")).intValue()).isEqualTo(2);

        for (String user : List.of(CHEF, CHEF, WAITER)) {
            mvc.perform(as(user, post("/api/v1/orders/{id}/advance", orderId))).andExpect(status().isOk());
        }
        payInFull(orderId, "CASH");

        Map<String, Object> closed = entry(mvc.perform(as(ADMIN, get("/api/v1/adisyon"))).andReturn().getResponse().getContentAsString(), billId);
        assertThat(closed.get("status")).isEqualTo("CLOSED");
        assertThat(closed.get("ettn")).isEqualTo(opened.get("ettn"));
        assertThat(closed.get("fiscalNumber")).as("the receipt's number closes it").isNotNull();
        assertThat(closed.get("delivery")).isEqualTo("SENT");
        assertThat(((Number) closed.get("sentVersion")).intValue()).isEqualTo(((Number) closed.get("version")).intValue());
        mvc.perform(as(WAITER, get("/api/v1/adisyon"))).andExpect(status().isForbidden());
    }

    private static Map<String, Object> entry(String view, long billId) {
        List<Map<String, Object>> found = JsonPath.read(view, "$.adisyons[?(@.billId == %d)]".formatted(billId));
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
