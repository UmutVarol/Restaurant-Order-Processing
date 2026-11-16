package com.umutvarol.restaurant.printing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.umutvarol.restaurant.support.ApiIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Tickets go to a fake network printer: a socket on localhost that keeps whatever it is sent. */
@TestPropertySource(properties = {
        "restaurant.printing.min-port=1024", "restaurant.printing.max-port=65535",
        "restaurant.printing.poll-interval=PT0.2S", "restaurant.printing.connect-timeout=500ms",
        "restaurant.printing.max-attempts=2"})
class PrintingIntegrationTest extends ApiIntegrationTest {

    private ServerSocket server;
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

    @BeforeEach
    void startFakePrinter() throws IOException {
        server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept(); InputStream in = socket.getInputStream()) {
                    received.add(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
                } catch (IOException ignored) {
                    // closed
                }
            }
        });
    }

    @AfterEach
    void stopFakePrinter() throws IOException {
        server.close();
    }

    @Test
    void eachPrinterGetsOnlyItsOwnDishes() throws Exception {
        long kitchen = createPrinter("Kitchen " + System.nanoTime(), server.getLocalPort(), "\"COLD\", \"HOT\"");
        long bar = createPrinter("Bar " + System.nanoTime(), server.getLocalPort(), "\"BAR\"");

        String order = mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "TAKEAWAY", "customerName": "Printer Test", "paymentMethod": "CASH",
                         "items": [{"menuItemId": %d, "quantity": 2}, {"menuItemId": %d, "quantity": 1}]}"""
                        .formatted(menuItemId("Adana Kebap"), menuItemId("Ayran"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long orderId = ((Number) JsonPath.read(order, "$.id")).longValue();

        String first = received.poll(10, TimeUnit.SECONDS);
        String second = received.poll(10, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        String kitchenTicket = first.contains("Adana") ? first : second;
        String barTicket = first.contains("Adana") ? second : first;
        assertThat(kitchenTicket).contains("2 x Adana Kebap").doesNotContain("Ayran").contains("PAKET #" + orderId);
        assertThat(barTicket).contains("1 x Ayran").doesNotContain("Adana");

        mvc.perform(as(ADMIN, get("/api/v1/printers/{id}/preview", kitchen).param("orderId", String.valueOf(orderId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns").value(48))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("2 X ADANA KEBAP")));

        // a reprint is marked as such
        mvc.perform(as(WAITER, post("/api/v1/orders/{id}/reprint", orderId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.length()").value(2));
        assertThat(received.poll(10, TimeUnit.SECONDS)).contains("TEKRAR BASKI");
        received.poll(10, TimeUnit.SECONDS);
        remove(kitchen);
        remove(bar);
    }

    @Test
    void aHeldCoursePrintsWhenFiredAndAVoidPrintsASlip() throws Exception {
        long kitchen = createPrinter("Courses " + System.nanoTime(), server.getLocalPort(), "\"COLD\", \"HOT\"");
        String order = mvc.perform(json(as(WAITER, post("/api/v1/orders")), """
                        {"type": "TAKEAWAY", "customerName": "Course Print", "paymentMethod": "CASH", "holdCourses": true,
                         "items": [{"menuItemId": %d, "quantity": 1}, {"menuItemId": %d, "quantity": 2}]}"""
                        .formatted(menuItemId("Mercimek Çorbası"), menuItemId("İskender"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long orderId = ((Number) JsonPath.read(order, "$.id")).longValue();
        long iskender = ((Number) JsonPath.read(order, "$.lines[1].id")).longValue();

        String first = received.poll(10, TimeUnit.SECONDS);
        assertThat(first).contains("1 x Mercimek").doesNotContain("Iskender").doesNotContain("x \u0098skender")
                .contains("Kurs 2 bekliyor");

        mvc.perform(as(WAITER, post("/api/v1/orders/{id}/courses/{course}/fire", orderId, 2))).andExpect(status().isOk());
        String fired = received.poll(10, TimeUnit.SECONDS);
        assertThat(fired).contains("KURS 2").contains("2 x ").doesNotContain("Mercimek");

        mvc.perform(json(as(ADMIN, post("/api/v1/orders/{id}/lines/{line}/adjust", orderId, iskender)), """
                        {"kind": "VOID", "quantity": 1, "reason": "Changed mind"}"""))
                .andExpect(status().isOk());
        String slip = received.poll(10, TimeUnit.SECONDS);
        assertThat(slip).contains("IPTAL").contains("-1 x ");
        remove(kitchen);
    }

    @Test
    void aPrinterThatIsOffIsRetriedThenReported() throws Exception {
        int deadPort;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            deadPort = closed.getLocalPort();
        }
        long printer = createPrinter("Off " + System.nanoTime(), deadPort, "\"DESSERT\"");
        mvc.perform(as(ADMIN, post("/api/v1/printers/{id}/test", printer))).andExpect(status().isAccepted());
        long deadline = System.currentTimeMillis() + 20_000;
        String jobs = "";
        while (System.currentTimeMillis() < deadline) {
            jobs = mvc.perform(as(ADMIN, get("/api/v1/print-jobs"))).andReturn().getResponse().getContentAsString();
            if (jobs.contains("\"FAILED\"")) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(jobs).contains("\"FAILED\"");
        mvc.perform(as(ADMIN, get("/api/v1/printers")))
                .andExpect(jsonPath("$[?(@.id == %d)].lastError".formatted(printer)).isNotEmpty());
        remove(printer);
    }

    @Test
    void printersOnlyOnThePrivateNetworkAndOnlyByManagers() throws Exception {
        mvc.perform(json(as(ADMIN, post("/api/v1/printers")), """
                        {"name": "Evil", "host": "169.254.169.254", "port": 9100, "paperWidth": "80",
                         "stations": ["HOT"], "enabled": true}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PRINTER_HOST_NOT_ALLOWED"));
        mvc.perform(json(as(ADMIN, post("/api/v1/printers")), """
                        {"name": "Evil", "host": "8.8.8.8", "port": 9100, "paperWidth": "80",
                         "stations": ["HOT"], "enabled": true}"""))
                .andExpect(jsonPath("$.code").value("PRINTER_HOST_NOT_ALLOWED"));
        mvc.perform(json(as(WAITER, post("/api/v1/printers")), """
                        {"name": "Mine", "host": "192.168.1.9", "port": 9100, "paperWidth": "80",
                         "stations": ["HOT"], "enabled": true}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPrintBridgeFetchesItsBranchsTicketsAndTheServerLeavesThemAlone() throws Exception {
        String created = mvc.perform(json(as(ADMIN, post("/api/v1/print-bridges")), """
                        {"name": "Mutfak bilgisayarı"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(created, "$.token");
        long bridge = ((Number) JsonPath.read(created, "$.bridge.id")).longValue();
        // an address only the restaurant's network knows: the server must never try it itself
        String body = mvc.perform(json(as(ADMIN, post("/api/v1/printers")), """
                        {"name": "Köprü %d", "host": "kitchen-printer.lan", "port": 9100, "paperWidth": "80",
                         "stations": ["HOT"], "enabled": true, "viaBridge": true}""".formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.viaBridge").value(true))
                .andReturn().getResponse().getContentAsString();
        long printer = ((Number) JsonPath.read(body, "$.id")).longValue();
        try {
            exerciseBridge(token, bridge, printer);
        } finally {
            remove(printer);
        }
    }

    private void exerciseBridge(String token, long bridge, long printer) throws Exception {
        mvc.perform(as(ADMIN, post("/api/v1/printers/{id}/test", printer))).andExpect(status().isAccepted());
        Thread.sleep(800);
        String jobs = mvc.perform(as(ADMIN, get("/api/v1/print-jobs"))).andReturn().getResponse().getContentAsString();
        Integer job = ((java.util.List<Integer>) JsonPath.read(jobs, "$[?(@.printerId == %d)].id".formatted(printer))).getFirst();
        assertThat(((java.util.List<Integer>) JsonPath.read(jobs, "$[?(@.id == %d)].attempts".formatted(job))).getFirst()).isZero();

        // the bridge takes it, once, with the printer's address and its bytes
        String claimed = mvc.perform(post("/api/v1/bridge/jobs/claim").header("X-Bridge-Token", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((java.util.List<Integer>) JsonPath.read(claimed, "$[*].id")).containsExactly(job);
        assertThat((String) JsonPath.read(claimed, "$[0].host")).isEqualTo("kitchen-printer.lan");
        byte[] ticket = java.util.Base64.getDecoder().decode((String) JsonPath.read(claimed, "$[0].bytes"));
        assertThat(ticket.length).isGreaterThan(20);
        mvc.perform(post("/api/v1/bridge/jobs/claim").header("X-Bridge-Token", token))
                .andExpect(jsonPath("$").isEmpty());

        mvc.perform(json(post("/api/v1/bridge/jobs/{id}/result", job).header("X-Bridge-Token", token), """
                        {"ok": true}""")).andExpect(status().isOk());
        String after = mvc.perform(as(ADMIN, get("/api/v1/print-jobs"))).andReturn().getResponse().getContentAsString();
        assertThat((java.util.List<String>) JsonPath.read(after, "$[?(@.id == %d)].status".formatted(job))).containsExactly("PRINTED");

        // without its token, or once switched off, a bridge gets nothing
        mvc.perform(post("/api/v1/bridge/jobs/claim")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/bridge/jobs/claim").header("X-Bridge-Token", "forged-token")).andExpect(status().isUnauthorized());
        mvc.perform(as(ADMIN, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/print-bridges/{id}", bridge)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/bridge/jobs/claim").header("X-Bridge-Token", token)).andExpect(status().isUnauthorized());
    }

    private long createPrinter(String name, int port, String stations) throws Exception {
        String body = mvc.perform(json(as(ADMIN, post("/api/v1/printers")), """
                        {"name": "%s", "host": "127.0.0.1", "port": %d, "paperWidth": "80",
                         "stations": [%s], "enabled": true}""".formatted(name, port, stations)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /**
     * Leaves no live printer behind for the other tests sharing this application context. A ticket the fake printer
     * has just received is still being marked printed; deleting the printer at that moment races that update (H2
     * reports the clash instead of waiting), so its queue settles first.
     */
    private void remove(long id) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            String jobs = mvc.perform(as(ADMIN, get("/api/v1/print-jobs"))).andReturn().getResponse().getContentAsString();
            List<Object> queued = JsonPath.read(jobs, "$[?(@.printerId == %d && @.status == 'QUEUED')]".formatted(id));
            if (queued.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }
        mvc.perform(as(ADMIN, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/printers/{id}", id)))
                .andExpect(status().isNoContent());
    }
}
