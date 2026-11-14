package com.umutvarol.restaurant.business;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An endpoint (or every endpoint of a controller) that belongs to a part of Sofra not every plan includes. A business
 * on a plan without it gets {@code 402 PLAN_FEATURE}; a restaurant's own server has every part.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresFeature {

    Feature value();
}
