package com.umutvarol.restaurant.channel;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Demo and tests: nothing leaves the building; the last updates are kept so the Channels screen can show them. */
class SimulatedChannelAdapter implements ChannelAdapter {

    private static final int KEPT = 30;

    /** One update that would have gone to the platform. */
    record Sent(Channel channel, String what, String target, String value, Instant at) {
    }

    private final Deque<Sent> sent = new ArrayDeque<>();
    private final Clock clock;

    SimulatedChannelAdapter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void updateStatus(Channel channel, String externalOrderId, ChannelStatus status) {
        remember(new Sent(channel, "status", externalOrderId, status.name(), clock.instant()));
    }

    @Override
    public void setAvailability(Channel channel, String externalItemId, boolean available) {
        remember(new Sent(channel, "availability", externalItemId, available ? "ON" : "OFF", clock.instant()));
    }

    private synchronized void remember(Sent update) {
        sent.addFirst(update);
        while (sent.size() > KEPT) {
            sent.removeLast();
        }
    }

    synchronized List<Sent> recent(Channel channel) {
        return sent.stream().filter(update -> update.channel() == channel).toList();
    }
}
