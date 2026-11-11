package com.umutvarol.restaurant.reservation.dto;

import com.umutvarol.restaurant.table.TableZone;

/** A table free for a party at a given time, with enough seats. */
public record TableOption(Long id, String code, int seats, TableZone zone) {
}
