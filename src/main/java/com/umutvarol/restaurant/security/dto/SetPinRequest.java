package com.umutvarol.restaurant.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Setting a PIN needs the password: a PIN session alone can never create or change a PIN. */
public record SetPinRequest(
        @NotBlank @Size(max = 100) String password,
        @NotBlank @Pattern(regexp = "\\d{4,6}") String pin) {

    @Override
    public String toString() {
        return "SetPinRequest[password=***, pin=***]";
    }
}
