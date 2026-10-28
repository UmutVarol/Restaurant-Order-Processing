package com.umutvarol.restaurant.channel;

import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import java.util.Arrays;
import java.util.Locale;

/** A delivery platform orders come from. */
public enum Channel {
    YEMEKSEPETI("Yemeksepeti"),
    GETIR("Getir Yemek"),
    TRENDYOL("Trendyol Go");

    private final String label;

    Channel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** @throws ResourceNotFoundException for a path segment that names no platform */
    static Channel fromPath(String value) {
        return Arrays.stream(values()).filter(channel -> channel.name().equalsIgnoreCase(value)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("channel", value.toLowerCase(Locale.ROOT)));
    }
}
