package com.umutvarol.restaurant.order.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint checking that an order carries what its fulfilment type needs:
 * a table for {@code DINE_IN}; an address and a phone number for {@code DELIVERY}.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = FulfilmentValidator.class)
public @interface ValidFulfilment {

    String message() default "Order is missing information required for its type";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
