package com.umutvarol.restaurant.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Every platform order in and every update out, on the {@code channel.audit} logger. No guest details are logged. */
@Component
class ChannelAuditLog {

    private static final Logger audit = LoggerFactory.getLogger("channel.audit");

    void received(Channel channel, String externalId, String outcome) {
        audit.info("channel.order channel={} id={} outcome={}", channel, clean(externalId), outcome);
    }

    void refused(Channel channel, String reason, String client) {
        audit.warn("channel.webhook.refused channel={} reason={} client={}", channel, reason, clean(client));
    }

    void sent(Channel channel, String what, String target, String outcome) {
        audit.info("channel.push channel={} what={} target={} outcome={}", channel, what, clean(target), outcome);
    }

    /** Values from outside never reach the log with line breaks (log forging). */
    private static String clean(String value) {
        return value == null ? "-" : value.replaceAll("[\\r\\n\\t]", "_");
    }
}
