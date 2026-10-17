package com.umutvarol.restaurant.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI restaurantOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        String version = buildProperties.getIfAvailable() != null
                ? buildProperties.getIfAvailable().getVersion()
                : "dev";
        return new OpenAPI()
                .info(new Info()
                        .title("Sofra — Restaurant Order Processing API")
                        .version(version)
                        .description("""
                                REST API behind the Sofra restaurant point-of-sale.

                                **Getting a token:** call `POST /api/v1/auth/login` with one of the demo accounts \
                                (`admin` / `admin123`, `waiter` / `waiter123`, `chef` / `chef123`), then press \
                                *Authorize* and paste the `accessToken`.

                                Errors follow RFC 9457 (`application/problem+json`) and always include a stable `code`.""")
                        .contact(new Contact().name("Umut Varol").url("https://github.com/UmutVarol"))
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
