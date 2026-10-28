package com.umutvarol.restaurant.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code code} is needed only when two-step sign-in is on: from the authenticator app, or a recovery code. */
public record LoginRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Size(max = 100) String password,
        @Size(max = 20) String code) {

    public LoginRequest(String username, String password) {
        this(username, password, null);
    }

    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=***, code=***]";
    }
}
