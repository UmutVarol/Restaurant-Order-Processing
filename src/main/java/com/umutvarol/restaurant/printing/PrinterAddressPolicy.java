package com.umutvarol.restaurant.printing;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where the server may send print jobs: a printer on the restaurant's own network. Checked when a printer is saved
 * and again, on the resolved address, right before every connection, so a host name that later resolves somewhere
 * else (DNS rebinding) is refused too.
 */
final class PrinterAddressPolicy {

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.:-]{1,100}");

    private PrinterAddressPolicy() {
    }

    static InetAddress resolve(String host, int port, PrintingProperties properties) {
        if (host == null || !HOST.matcher(host).matches()) {
            throw refused(host, "not a host name or IP address");
        }
        if (port < properties.minPort() || port > properties.maxPort()) {
            throw new BusinessRuleException("PRINTER_PORT_NOT_ALLOWED", "Printers listen on ports %d-%d"
                    .formatted(properties.minPort(), properties.maxPort()),
                    Map.of("min", properties.minPort(), "max", properties.maxPort()));
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException ex) {
            throw new BusinessRuleException("PRINTER_HOST_UNKNOWN", "Cannot find " + host, Map.of("host", host));
        }
        if (!isPrivate(address)) {
            throw refused(host, "not on the local network");
        }
        return address;
    }

    /** RFC 1918, carrier-grade NAT, loopback and IPv6 unique-local addresses; never link-local (cloud metadata). */
    static boolean isPrivate(InetAddress address) {
        if (address.isLinkLocalAddress() || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; // 100.64.0.0/10
        }
        return address instanceof Inet6Address && (b[0] & 0xfe) == 0xfc; // fc00::/7
    }

    private static BusinessRuleException refused(String host, String why) {
        return new BusinessRuleException("PRINTER_HOST_NOT_ALLOWED", "Printer address %s is %s".formatted(host, why),
                Map.of("host", String.valueOf(host)));
    }
}
