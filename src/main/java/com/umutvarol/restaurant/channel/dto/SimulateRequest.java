package com.umutvarol.restaurant.channel.dto;

/** Demo: a made-up platform order, optionally with an item nobody has mapped yet. */
public record SimulateRequest(Boolean withUnmappedItem) {
}
