package com.umutvarol.restaurant.floor;

import java.time.Instant;
import java.util.Map;

/**
 * Since when guests at each table have been waiting for someone: a waiter called, the bill asked for, a cart sent.
 * Implemented by the guest feature, so the floor plan can show it without depending on it.
 */
public interface TableCalls {

    TableCalls NONE = Map::of;

    /** The oldest request still open at each table, by table id. */
    Map<Long, Instant> waitingSince();
}
