package com.umutvarol.restaurant.billing;

import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.business.BusinessProfile;
import com.umutvarol.restaurant.business.RequiresFeature;
import com.umutvarol.restaurant.common.error.SubscriptionRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Keeps a plan to what it includes: an endpoint marked {@link RequiresFeature} answers {@code 402 PLAN_FEATURE} otherwise. */
@Component
class FeatureGuard implements HandlerInterceptor {

    private final BusinessProfile profile;

    FeatureGuard(BusinessProfile profile) {
        this.profile = profile;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || BusinessContext.current() == null) {
            return true;
        }
        RequiresFeature required = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiresFeature.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresFeature.class);
        }
        if (required != null && !profile.includes(required.value())) {
            throw new SubscriptionRequiredException("PLAN_FEATURE", "This is not part of the " + profile.plan() + " plan",
                    Map.of("feature", required.value().name(), "plan", profile.plan().name()));
        }
        return true;
    }
}
