package com.umutvarol.restaurant.security.dto;

/**
 * @param enabled           two-step sign-in is on for this account
 * @param available         the server can store TOTP secrets (TOTP_ENCRYPTION_KEY is set)
 * @param setupRequired     this manager must turn it on before refunds, closing the day or registering tills
 * @param recoveryCodesLeft unused recovery codes
 */
public record TwoFactorStatus(boolean enabled, boolean available, boolean setupRequired, int recoveryCodesLeft) {
}
