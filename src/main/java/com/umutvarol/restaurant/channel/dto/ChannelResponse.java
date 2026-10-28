package com.umutvarol.restaurant.channel.dto;

import com.umutvarol.restaurant.channel.Channel;
import com.umutvarol.restaurant.channel.ChannelMode;
import java.time.Instant;
import java.util.List;

/**
 * A platform and how it is connected. Credentials are never shown.
 *
 * @param recentUpdates what was (or, simulated, would have been) sent to the platform lately
 */
public record ChannelResponse(Channel channel, String label, ChannelMode mode, long attention, int mappings,
                              List<Update> recentUpdates) {

    public record Update(String what, String target, String value, Instant at) {
    }
}
