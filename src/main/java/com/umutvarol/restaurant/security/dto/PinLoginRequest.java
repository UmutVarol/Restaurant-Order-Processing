package com.umutvarol.restaurant.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PinLoginRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Pattern(regexp = "\\d{4,6}") String pin) {

    @Override
    public String toString() {
        return "PinLoginRequest[username=" + username + ", pin=***]";
    }
}
