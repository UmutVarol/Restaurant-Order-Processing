package com.umutvarol.restaurant.integration;

import com.umutvarol.restaurant.business.Feature;

/** What a business can connect on its own: its e-Arşiv and e-Adisyon integrators and each delivery platform. */
public enum IntegrationKind {
    E_ARSIV(Feature.FISCAL, false),
    E_ADISYON(Feature.FISCAL, false),
    YEMEKSEPETI(Feature.DELIVERY_PLATFORMS, true),
    GETIR(Feature.DELIVERY_PLATFORMS, true),
    TRENDYOL(Feature.DELIVERY_PLATFORMS, true);

    private final Feature feature;
    private final boolean platform;

    IntegrationKind(Feature feature, boolean platform) {
        this.feature = feature;
        this.platform = platform;
    }

    /** The plan feature it belongs to. */
    public Feature feature() {
        return feature;
    }

    /** A delivery platform: it signs the orders it sends us, and may take status updates back. */
    public boolean platform() {
        return platform;
    }
}
