package com.umutvarol.restaurant.order.validation;

import com.umutvarol.restaurant.order.dto.PlaceOrderRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Reports each missing field individually so that the UI can highlight exactly what to fill in. */
public class FulfilmentValidator implements ConstraintValidator<ValidFulfilment, PlaceOrderRequest> {

    @Override
    public boolean isValid(PlaceOrderRequest request, ConstraintValidatorContext context) {
        if (request == null || request.type() == null) {
            return true; // @NotNull on the field reports a missing type
        }
        context.disableDefaultConstraintViolation();
        return switch (request.type()) {
            case DINE_IN -> require(request.tableId() != null, "tableId", "is required for dine-in orders", context);
            case DELIVERY -> require(!isBlank(request.deliveryAddress()), "deliveryAddress",
                    "is required for delivery orders", context)
                    & require(!isBlank(request.customerPhone()), "customerPhone",
                    "is required for delivery orders", context);
            case TAKEAWAY -> true;
        };
    }

    private static boolean require(boolean condition, String field, String message, ConstraintValidatorContext context) {
        if (!condition) {
            context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
        }
        return condition;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
