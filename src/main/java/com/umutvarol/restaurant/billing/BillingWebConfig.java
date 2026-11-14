package com.umutvarol.restaurant.billing;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts the subscription guard in front of every API call. */
@Configuration
class BillingWebConfig implements WebMvcConfigurer {

    private final ObjectProvider<SubscriptionGuard> guard;

    BillingWebConfig(ObjectProvider<SubscriptionGuard> guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard.getObject()).addPathPatterns("/api/**");
    }
}
