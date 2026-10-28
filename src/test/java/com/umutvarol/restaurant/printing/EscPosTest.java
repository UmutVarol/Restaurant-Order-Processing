package com.umutvarol.restaurant.printing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class EscPosTest {

    private static final PrintingProperties PROPERTIES = new PrintingProperties(true, 9100, 9109, Duration.ofSeconds(1),
            Duration.ofSeconds(1), 3, "tr");

    @Test
    void resetsSelectsTurkishAndCuts() {
        byte[] bytes = new EscPos().line("Çay ve şiş").cut();
        assertThat(Arrays.copyOfRange(bytes, 0, 5)).containsExactly(0x1b, '@', 0x1b, 't', 13);
        assertThat(Arrays.copyOfRange(bytes, bytes.length - 4, bytes.length)).containsExactly(0x1d, 'V', 66, 3);
        // Ç = 0x80, ş = 0x9f in code page 857
        assertThat(indexOf(bytes, (byte) 0x80)).isPositive();
        assertThat(indexOf(bytes, (byte) 0x9f)).isPositive();
    }

    @Test
    void dropsEmojiAndSpellsOutTheLiraSign() {
        byte[] bytes = new EscPos().line("🍢 Adana ₺520").cut();
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(text).contains(" Adana TL520").doesNotContain("?");
    }

    @Test
    void wrapsLongLinesAtWordBoundaries() {
        assertThat(KitchenTicket.wrap("2 x Hand-minced lamb kebab with chilli", 16))
                .containsExactly("2 x Hand-minced", "lamb kebab with", "chilli");
        assertThat(KitchenTicket.wrap("Supercalifragilistic", 8)).containsExactly("Supercal", "ifragili", "stic");
    }

    @Test
    void printersMayOnlyBeOnTheLocalNetwork() throws Exception {
        assertThat(PrinterAddressPolicy.isPrivate(InetAddress.getByName("192.168.1.50"))).isTrue();
        assertThat(PrinterAddressPolicy.isPrivate(InetAddress.getByName("10.0.0.7"))).isTrue();
        assertThat(PrinterAddressPolicy.isPrivate(InetAddress.getByName("172.16.4.2"))).isTrue();
        assertThat(PrinterAddressPolicy.isPrivate(InetAddress.getByName("100.64.0.9"))).isTrue();
        assertThat(PrinterAddressPolicy.isPrivate(InetAddress.getByName("fd00::12"))).isTrue();
        assertThat(PrinterAddressPolicy.isPrivate(InetAddress.getByName("8.8.8.8"))).isFalse();
        assertThat(PrinterAddressPolicy.isPrivate(InetAddress.getByName("169.254.169.254"))).isFalse(); // cloud metadata
        assertThat(PrinterAddressPolicy.isPrivate(InetAddress.getByName("0.0.0.0"))).isFalse();
        assertThat(PrinterAddressPolicy.isPrivate(InetAddress.getByName("2001:4860:4860::8888"))).isFalse();
    }

    @Test
    void theAddressAndPortAreCheckedTogether() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PrinterAddressPolicy.resolve("192.168.1.50", 22, PROPERTIES))
                .hasFieldOrPropertyWithValue("code", "PRINTER_PORT_NOT_ALLOWED");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PrinterAddressPolicy.resolve("8.8.8.8", 9100, PROPERTIES))
                .hasFieldOrPropertyWithValue("code", "PRINTER_HOST_NOT_ALLOWED");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PrinterAddressPolicy.resolve("evil.example/x", 9100, PROPERTIES))
                .hasFieldOrPropertyWithValue("code", "PRINTER_HOST_NOT_ALLOWED");
        assertThat(PrinterAddressPolicy.resolve("192.168.1.50", 9100, PROPERTIES).getHostAddress()).isEqualTo("192.168.1.50");
    }

    private static int indexOf(byte[] bytes, byte value) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == value) {
                return i;
            }
        }
        return -1;
    }
}
