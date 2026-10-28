package com.umutvarol.restaurant.channel;

/**
 * The port to one delivery platform. Orders come in through the signed webhook ({@link ChannelWebhookController});
 * this sends what changed back out. One implementation per way of talking to a platform.
 */
interface ChannelAdapter {

    /** The kitchen started on the order (or it was rejected, cancelled, made ready, handed over). */
    void updateStatus(Channel channel, String externalOrderId, ChannelStatus status);

    /** A mapped dish sold out or came back. */
    void setAvailability(Channel channel, String externalItemId, boolean available);
}
