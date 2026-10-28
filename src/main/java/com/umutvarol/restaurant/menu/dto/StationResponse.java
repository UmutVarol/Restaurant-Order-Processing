package com.umutvarol.restaurant.menu.dto;

import com.umutvarol.restaurant.menu.Station;

public record StationResponse(Long id, String code, String name, int targetMinutes) {

    public static StationResponse from(Station station) {
        return new StationResponse(station.getId(), station.getCode(), station.getName(), station.getTargetMinutes());
    }
}
