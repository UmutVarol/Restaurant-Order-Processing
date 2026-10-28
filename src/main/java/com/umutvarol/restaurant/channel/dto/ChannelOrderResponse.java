package com.umutvarol.restaurant.channel.dto;

import com.umutvarol.restaurant.channel.Channel;
import com.umutvarol.restaurant.channel.ChannelOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * @param unmapped     the platform's items that are not mapped to a dish yet, when that is what stopped the order
 * @param priceDiffers the platform's prices do not match our menu's
 */
public record ChannelOrderResponse(Long id, Channel channel, String externalId, ChannelOrder.Status status, Long orderId,
                                   String customerName, int itemCount, BigDecimal platformTotal, BigDecimal ourTotal,
                                   boolean priceDiffers, String problemCode, String problem, List<Unmapped> unmapped,
                                   String syncedStatus, String syncError, Instant receivedAt) {

    public record Unmapped(String externalId, String name) {
    }
}
