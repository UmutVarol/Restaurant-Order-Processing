package com.umutvarol.restaurant.security.dto;

/** The device token is returned once, here; the server keeps only its hash. */
public record DeviceRegistration(DeviceResponse device, String token) {

    @Override
    public String toString() {
        return "DeviceRegistration[device=" + device + ", token=***]";
    }
}
