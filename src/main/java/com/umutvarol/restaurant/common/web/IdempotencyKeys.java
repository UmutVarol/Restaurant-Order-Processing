package com.umutvarol.restaurant.common.web;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The {@code Idempotency-Key} header that makes a retried POST safe: the client makes up a key once, and sending the
 * same request again with it returns what the first one did instead of doing it twice. Tills that queue orders while
 * offline rely on it.
 */
public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{8,80}");

    private IdempotencyKeys() {
    }

    /** @throws BusinessRuleException {@code IDEMPOTENCY_KEY_INVALID} unless the key is null or 8 to 80 safe characters */
    public static void requireValid(@Nullable String key) {
        if (key != null && !FORMAT.matcher(key).matches()) {
            throw new BusinessRuleException("IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must be 8 to 80 letters, digits, '-' or '_'");
        }
    }

    public static BusinessRuleException reused(String what) {
        return new BusinessRuleException("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for " + what);
    }
}
