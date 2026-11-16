package com.umutvarol.restaurant.billing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.billing.BillingProvider.SubscriptionUpdate;
import com.umutvarol.restaurant.business.BillingInterval;
import com.umutvarol.restaurant.business.Business.Plan;
import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.business.BusinessRegistry;
import com.umutvarol.restaurant.business.BusinessRepository;
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
 * Sofra Cloud's subscriptions with the simulated provider: a trial business picks a plan, the Starter plan's limits
 * hold, and a lapsed subscription makes the business read-only (it still reads and pays) until it pays again.
 */
@SpringBootTest(properties = {"restaurant.cloud.enabled=true", "restaurant.cloud.signup-enabled=true",
        "restaurant.cloud.base-domain=sofra.test", "restaurant.demo.enabled=false",
        "restaurant.security.rate-limit.public-requests-per-minute=100000",
        "restaurant.cloud.signups-per-client-per-hour=1000", "restaurant.billing.provider=SIMULATED",
        "restaurant.billing.allow-simulated=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubscriptionIntegrationTest {

    private static final AtomicInteger NUMBERS = new AtomicInteger();

    @Autowired
    MockMvc mvc;
    @Autowired
    SubscriptionService subscriptions;
    @Autowired
    BusinessRepository businesses;

    @Test
    void aTrialPicksStarterAndItsLimitsHold() throws Exception {
        String[] opened = open();
        String owner = opened[1];
        mvc.perform(with(owner, get("/api/v1/plan")))
                .andExpect(jsonPath("$.features.length()").value(5));
        mvc.perform(with(owner, get("/api/v1/billing")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.plan").value("BUSINESS"))
                .andExpect(jsonPath("$.writable").value(true))
                .andExpect(jsonPath("$.billingEnabled").value(true))
                .andExpect(jsonPath("$.usage.tables").value(12))
                .andExpect(jsonPath("$.offers[0].monthlyCentsPerBranch").value(3900))
                .andExpect(jsonPath("$.offers[1].monthlyCentsPerBranch").value(7900))
                .andExpect(jsonPath("$.offers[1].yearlyCentsPerBranch").value(79000))
                .andExpect(jsonPath("$.offers[1].listCentsPerBranch").value(0))
                .andExpect(jsonPath("$.offers[2].plan").value("CHAIN"))
                // a chain's own price: below its list price, above the Business plan
                .andExpect(jsonPath("$.offers[2].monthlyCentsPerBranch").value(8900))
                .andExpect(jsonPath("$.offers[2].listCentsPerBranch").value(11900))
                .andExpect(jsonPath("$.offers[2].billedBranches").value(3));

        mvc.perform(with(owner, json(post("/api/v1/billing/checkout"), """
                        {"plan": "STARTER", "interval": "MONTHLY"}""")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.endsWith("#/billing")));
        mvc.perform(with(owner, get("/api/v1/billing")))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.plan").value("STARTER"))
                .andExpect(jsonPath("$.billedBranches").value(1))
                .andExpect(jsonPath("$.limits.tables").value(15));

        // the parts Starter leaves out answer 402, and the app is told so
        mvc.perform(with(owner, get("/api/v1/plan")))
                .andExpect(jsonPath("$.plan").value("STARTER"))
                .andExpect(jsonPath("$.features").isEmpty());
        mvc.perform(with(owner, get("/api/v1/shifts")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PLAN_FEATURE"))
                .andExpect(jsonPath("$.parameters.feature").value("SHIFTS_AND_TIPS"));
        mvc.perform(with(owner, get("/api/v1/fiscal/documents"))).andExpect(status().isPaymentRequired());
        mvc.perform(with(owner, get("/api/v1/channels"))).andExpect(status().isPaymentRequired());

        // fifteen tables, and no sixteenth; one branch, and no second
        for (int code = 13; code <= 15; code++) {
            mvc.perform(with(owner, json(post("/api/v1/tables"), """
                            {"code": "%d", "seats": 2, "zone": "INDOOR"}""".formatted(code)))).andExpect(status().isCreated());
        }
        mvc.perform(with(owner, json(post("/api/v1/tables"), """
                        {"code": "16", "seats": 2, "zone": "INDOOR"}""")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PLAN_LIMIT"))
                .andExpect(jsonPath("$.parameters.limit").value(15));
        mvc.perform(with(owner, json(post("/api/v1/branches"), """
                        {"code": "ikinci", "name": "İkinci"}""")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PLAN_LIMIT"));
    }

    @Test
    void aLapsedSubscriptionReadsButChangesNothingUntilPaid() throws Exception {
        String[] opened = open();
        String owner = opened[1];
        long id = BusinessContext.acrossAll(() -> businesses.findBySlug(opened[0]).orElseThrow().getId());
        subscriptions.apply("SIMULATED", new SubscriptionUpdate(id, "c", "s-" + id, Plan.BUSINESS, BillingInterval.MONTHLY, 1,
                SubscriptionUpdate.State.CANCELLED, null, null));

        mvc.perform(with(owner, get("/api/v1/tables"))).andExpect(status().isOk());
        mvc.perform(with(owner, json(post("/api/v1/tables"), """
                        {"code": "20", "seats": 2, "zone": "INDOOR"}""")))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_INACTIVE"));
        mvc.perform(with(owner, get("/api/v1/billing"))).andExpect(jsonPath("$.writable").value(false));

        // paying opens it again at once
        mvc.perform(with(owner, json(post("/api/v1/billing/checkout"), """
                        {"plan": "BUSINESS", "interval": "YEARLY"}"""))).andExpect(status().isOk());
        mvc.perform(with(owner, json(post("/api/v1/tables"), """
                        {"code": "20", "seats": 2, "zone": "INDOOR"}"""))).andExpect(status().isCreated());
    }

    @Test
    void webhooksNeedAKnownProviderAndASignature() throws Exception {
        mvc.perform(post("/api/v1/public/billing/webhook/stripe").content("{}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/public/billing/webhook/simulated").content("{}")).andExpect(status().isBadRequest());
    }

    private String[] open() throws Exception {
        String name = "Abone " + NUMBERS.incrementAndGet();
        String address = BusinessRegistry.suggestAddress(name);
        String body = mvc.perform(json(post("/api/v1/public/signup"), """
                        {"businessName": "%s", "email": "owner@example.com", "ownerName": "Sahibi",
                         "username": "%s-owner", "password": "masa dört hazır 9"}""".formatted(name, address)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new String[] {address, JsonPath.read(body, "$.session.accessToken")};
    }

    private static MockHttpServletRequestBuilder with(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
