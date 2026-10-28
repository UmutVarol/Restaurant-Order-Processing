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

    private long createPrinter(String name, int port, String stations) throws Exception {
        String body = mvc.perform(json(as(ADMIN, post("/api/v1/printers")), """
                        {"name": "%s", "host": "127.0.0.1", "port": %d, "paperWidth": "80",
                         "stations": [%s], "enabled": true}""".formatted(name, port, stations)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** Leaves no live printer behind for the other tests sharing this application context. */
    private void remove(long id) throws Exception {
        mvc.perform(as(ADMIN, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/printers/{id}", id)))
                .andExpect(status().isNoContent());
    }
}
