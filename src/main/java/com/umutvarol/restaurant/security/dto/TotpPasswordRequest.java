package com.umutvarol.restaurant.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The password, and for turning two-step sign-in off also a code. */
public record TotpPasswordRequest(@NotBlank @Size(max = 100) String password, @Size(max = 20) String code) {

    @Override
    public String toString() {
        return "TotpPasswordRequest[password=***, code=***]";
    }
}
