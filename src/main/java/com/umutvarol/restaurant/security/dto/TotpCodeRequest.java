package com.umutvarol.restaurant.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A code from the authenticator app (six digits) or a recovery code. */
public record TotpCodeRequest(@NotBlank @Size(max = 20) String code) {

    @Override
    public String toString() {
        return "TotpCodeRequest[code=***]";
    }
}
