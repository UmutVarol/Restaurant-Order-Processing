package com.umutvarol.restaurant.security;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a password must be (OWASP Authentication Cheat Sheet, NIST SP 800-63B): long enough, not the username,
 * not one of the passwords every attacker tries first. No composition rules, which only make people write
 * passwords down; length is what counts.
 */
final class PasswordPolicy {

    static final int MIN_LENGTH = 10;
    static final int MAX_LENGTH = 128;

    /** The first passwords tried against any restaurant: common lists plus the obvious local ones. */
    private static final Set<String> COMMON = Set.of(
            "1234567890", "0123456789", "12345678910", "1111111111", "0000000000", "1234512345", "qwertyuiop",
            "password12", "password123", "password1!", "passw0rd12", "administrator", "admin12345", "admin123456",
            "letmein123", "welcome123", "iloveyou12", "sifre12345", "şifre12345", "parola1234", "parola12345",
            "restoran123", "restaurant", "restaurant1", "restaurant123", "sofra12345", "sofra123456", "galatasaray",
            "fenerbahce", "fenerbahçe", "besiktas1903", "trabzonspor", "1q2w3e4r5t", "q1w2e3r4t5", "asdfghjkl1",
            "zxcvbnm123", "abcdefghij", "abc1234567", "changeme123", "temp123456");

    /** For temporary passwords: no 0/O, 1/l/I, easy to read out and type on a tablet. */
    private static final char[] READABLE = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordPolicy() {
    }

    static void check(String username, String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw weak("PASSWORD_TOO_SHORT", "Use at least %d characters.".formatted(MIN_LENGTH));
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower) || password.chars().distinct().count() < 4) {
            throw weak("PASSWORD_TOO_COMMON", "That password is too easy to guess.");
        }
        if (username != null && username.length() >= 3 && lower.contains(username.toLowerCase(Locale.ROOT))) {
            throw weak("PASSWORD_CONTAINS_USERNAME", "The password must not contain the username.");
        }
    }

    /** Twelve readable characters in three groups (about 59 bits), e.g. {@code k7mq-x3ta-9pwe}. */
    static String temporary() {
        StringBuilder out = new StringBuilder(14);
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) {
                out.append('-');
            }
            out.append(READABLE[RANDOM.nextInt(READABLE.length)]);
        }
        return out.toString();
    }

    private static BusinessRuleException weak(String code, String message) {
        return new BusinessRuleException(code, message, Map.of("min", MIN_LENGTH));
    }
}
