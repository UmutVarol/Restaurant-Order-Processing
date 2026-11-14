package com.umutvarol.restaurant.floor.dto;

import com.umutvarol.restaurant.table.TableZone;
import java.time.Instant;

public record TableResponse(Long id, String code, int seats, TableZone zone, Instant qrIssuedAt) {
}
