package com.umutvarol.restaurant.common.error;

import java.util.Map;

/** A requested resource does not exist. Mapped to {@code 404 Not Found}. */
public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String resource, Object id) {
        super(resource.toUpperCase() + "_NOT_FOUND",
                "%s %s was not found".formatted(capitalize(resource), id),
                Map.of("id", String.valueOf(id)));
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1).replace('_', ' ');
    }
}
