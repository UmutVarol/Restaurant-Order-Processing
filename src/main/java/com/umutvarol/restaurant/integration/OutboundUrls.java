package com.umutvarol.restaurant.integration;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;

/**
 * Addresses a business types in for the server to call (an e-Arşiv integrator, a platform's API). The server must
 * never be turned against its own network: only https to a name that resolves to public addresses only is taken,
 * checked when saved and again before each connection is built.
 */
public final class OutboundUrls {

    private OutboundUrls() {
    }

    /** @throws BusinessRuleException {@code INTEGRATION_URL_INVALID} for anything else */
    public static URI requirePublicHttps(String value) {
        URI url;
        try {
            url = URI.create(value.strip());
        } catch (IllegalArgumentException ex) {
            throw invalid(value);
        }
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null
                || url.getRawQuery() != null || url.getRawFragment() != null) {
            throw invalid(value);
        }
        int port = url.getPort();
        if (port != -1 && port != 443) {
            throw invalid(value);
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            throw invalid(value);
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (!isPublic(address)) {
                    throw invalid(value);
                }
            }
        } catch (UnknownHostException ex) {
            throw new BusinessRuleException("INTEGRATION_URL_UNKNOWN", "This address cannot be found", Map.of("url", value));
        }
        return url;
    }

    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet6Address) {
            return (b[0] & 0xfe) != 0xfc; // unique local fc00::/7
        }
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        return !(first == 0 || first >= 224                      // this network, multicast and reserved
                || (first == 100 && second >= 64 && second < 128) // carrier-grade NAT
                || (first == 192 && second == 0 && (b[2] & 0xff) == 0)
                || (first == 198 && (second == 18 || second == 19)));
    }

    private static BusinessRuleException invalid(String value) {
        return new BusinessRuleException("INTEGRATION_URL_INVALID",
                "Use the provider's https address; addresses on a private network are not allowed", Map.of("url", value));
    }
}
