package com.umutvarol.restaurant.order;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class OrderApiIntegrationTest extends ApiIntegrationTest {

    @Test
    void dineInOrderGoesFromTheTillThroughTheKitchenToThePaidBill() throws Exception {
        String created = mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "DINE_IN", "customerName": "Umut Varol", "tableId": %d, "paymentMethod": "CARD",
                         "couponCode": "hosgeldin", "note": "Anniversary",
                         "items": [{"menuItemId": %d, "quantity": 2, "note": "extra yogurt"},
                                   {"menuItemId": %d, "quantity": 1}]}
                        """.formatted(tableId("W1"), menuItemId("İskender"), menuItemId("Künefe"))))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern(".*/api/v1/orders/\\d+")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.nextStatus").value("PREPARING"))
                .andExpect(jsonPath("$.cancellable").value(true))
                .andExpect(jsonPath("$.table.code").value("W1"))
                .andExpect(jsonPath("$.couponCode").value("HOSGELDIN"))
                .andExpect(jsonPath("$.itemCount").value(3))
                .andExpect(jsonPath("$.lines[0].name").value("İskender"))
                .andExpect(jsonPath("$.lines[0].note").value("extra yogurt"))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(1120.00))
                .andExpect(jsonPath("$.pricing.subtotal").value(1380.00))
                .andExpect(jsonPath("$.pricing.discount").value(138.00))
                .andExpect(jsonPath("$.pricing.tax").value(124.20))
                .andExpect(jsonPath("$.pricing.deliveryFee").value(0.00))
                .andExpect(jsonPath("$.pricing.total").value(1366.20))
                .andExpect(jsonPath("$.createdBy").value(WAITER))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();

        advance(CHEF, id, "PREPARING");
        advance(CHEF, id, "READY");
        advance(WAITER, id, "SERVED");
        mvc.perform(as(WAITER, post("/api/v1/orders/{id}/advance", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.nextStatus").doesNotExist())
                .andExpect(jsonPath("$.history[*].status").value(
                        containsInAnyOrder("PENDING", "PREPARING", "READY", "SERVED", "COMPLETED")))
                .andExpect(jsonPath("$.history[1].by").value(CHEF));

        mvc.perform(as(CHEF, post("/api/v1/orders/{id}/advance", id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_ADVANCEABLE"));
    }

    @Test
    void deliveryOrdersFollowTheCourierPath() throws Exception {
        String created = mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "DELIVERY", "customerName": "Zeynep", "customerPhone": "+90 532 111 22 33",
                         "deliveryAddress": "Moda Cad. 12, Kadıköy", "paymentMethod": "ONLINE",
                         "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(menuItemId("Lahmacun"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pricing.deliveryFee").value(39.90))
                .andExpect(jsonPath("$.pricing.total").value(281.90))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();

        advance(CHEF, id, "PREPARING");
        advance(CHEF, id, "READY");
        advance(WAITER, id, "OUT_FOR_DELIVERY");
        advance(WAITER, id, "COMPLETED");
    }

    @Test
    void reportsEveryMissingDeliveryDetail() throws Exception {
        mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "DELIVERY", "customerName": "", "paymentMethod": "CARD", "items": []}"""))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(
                        containsInAnyOrder("customerName", "customerPhone", "deliveryAddress", "items")));
    }

    @Test
    void rejectsUnreadableJson() throws Exception {
        mvc.perform(json(as(WAITER, post("/api/v1/orders")), "{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void kitchenStaffCannotTakeOrders() throws Exception {
        mvc.perform(json(as(CHEF, post("/api/v1/orders")), "{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anonymousCallersMustSignIn() throws Exception {
        mvc.perform(get("/api/v1/orders/active"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void quotesReportInvalidCouponsWithoutFailing() throws Exception {
        mvc.perform(json(as(WAITER, post("/api/v1/orders/quote")), """
                        {"type": "TAKEAWAY", "couponCode": "YAZ2025",
                         "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(menuItemId("Adana Kebap"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupon.applied").value(false))
                .andExpect(jsonPath("$.coupon.errorCode").value("COUPON_EXPIRED"))
                .andExpect(jsonPath("$.pricing.total").value(572.00));
    }

    @Test
    void placingAnOrderWithAnUnusableCouponFails() throws Exception {
        mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "TAKEAWAY", "customerName": "Can", "paymentMethod": "CASH", "couponCode": "SOFRA20",
                         "items": [{"menuItemId": %d, "quantity": 1}]}""".formatted(menuItemId("Ayran"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("COUPON_MINIMUM_NOT_MET"))
                .andExpect(jsonPath("$.parameters.minimumSubtotal").value(1000.00));
    }

    @Test
    void waitersCanCancelOrdersUntilTheKitchenIsDone() throws Exception {
        long id = placeTakeaway("Cancel Me", "Mantı", 1);

        mvc.perform(json(as(CHEF, post("/api/v1/orders/{id}/cancel", id)), """
                        {"reason": "chef says no"}"""))
                .andExpect(status().isForbidden());
        mvc.perform(json(as(WAITER, post("/api/v1/orders/{id}/cancel", id)), """
                        {"reason": " "}"""))
                .andExpect(status().isBadRequest());
        mvc.perform(json(as(WAITER, post("/api/v1/orders/{id}/cancel", id)), """
                        {"reason": "Guest changed their mind"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason").value("Guest changed their mind"))
                .andExpect(jsonPath("$.cancellable").value(false));

        long ready = placeTakeaway("Too Late", "Mantı", 1);
        advance(CHEF, ready, "PREPARING");
        advance(CHEF, ready, "READY");
        mvc.perform(json(as(WAITER, post("/api/v1/orders/{id}/cancel", ready)), """
                        {"reason": "too late"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));
    }

    @Test
    void searchesByCustomerNumberAndStatus() throws Exception {
        long first = placeTakeaway("Searchable Selin", "Humus", 1);
        long second = placeTakeaway("Searchable Selin", "Ayran", 2);
        advance(CHEF, second, "PREPARING");

        mvc.perform(as(WAITER, get("/api/v1/orders").param("q", "searchable selin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second))
                .andExpect(jsonPath("$.content[1].id").value(first));
        mvc.perform(as(WAITER, get("/api/v1/orders").param("q", "Searchable").param("status", "PREPARING")))
                .andExpect(jsonPath("$.content[*].id").value(hasItem((int) second)))
                .andExpect(jsonPath("$.content[*].status").value(everyItem(is("PREPARING"))));
        mvc.perform(as(WAITER, get("/api/v1/orders").param("q", "#" + first)))
                .andExpect(jsonPath("$.content[*].id").value(hasItem((int) first)));
        mvc.perform(as(WAITER, get("/api/v1/orders").param("size", "1")))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.first").value(true));
    }

    @Test
    void activeOrdersExcludeFinishedOnes() throws Exception {
        long open = placeTakeaway("Active Ali", "Çoban Salata", 1);
        long cancelled = placeTakeaway("Gone Gül", "Çoban Salata", 1);
        mvc.perform(json(as(WAITER, post("/api/v1/orders/{id}/cancel", cancelled)), """
                {"reason": "left"}""")).andExpect(status().isOk());

        mvc.perform(as(CHEF, get("/api/v1/orders/active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem((int) open)))
                .andExpect(jsonPath("$[*].id").value(not(hasItem((int) cancelled))));
    }

    @Test
    void unknownOrdersAreNotFound() throws Exception {
        mvc.perform(as(WAITER, get("/api/v1/orders/{id}", 987654)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void rejectsUnknownSortProperties() throws Exception {
        mvc.perform(as(WAITER, get("/api/v1/orders").param("sort", "password")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT"));
    }

    private void advance(String user, long id, String expectedStatus) throws Exception {
        mvc.perform(as(user, post("/api/v1/orders/{id}/advance", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expectedStatus));
    }
}
