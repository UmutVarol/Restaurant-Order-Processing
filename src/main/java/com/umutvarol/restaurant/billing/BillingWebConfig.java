package com.umutvarol.restaurant.billing;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts the subscription and plan guards in front of every API call. */
@Configuration
class BillingWebConfig implements WebMvcConfigurer {

    private final ObjectProvider<SubscriptionGuard> guard;
    private final ObjectProvider<FeatureGuard> features;

    BillingWebConfig(ObjectProvider<SubscriptionGuard> guard, ObjectProvider<FeatureGuard> features) {
        this.guard = guard;
        this.features = features;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard.getObject()).addPathPatterns("/api/**");
        registry.addInterceptor(features.getObject()).addPathPatterns("/api/**");
    }
}
