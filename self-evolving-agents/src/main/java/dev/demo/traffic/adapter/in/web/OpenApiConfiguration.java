package dev.demo.traffic.adapter.in.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Self-Evolving Traffic Demo API").version("v1")
                        .description("Admin endpoints require the X-Demo-Admin header or a Bearer token."))
                .components(new Components().addSecuritySchemes("admin",
                        new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Demo-Admin")))
                .addSecurityItem(new SecurityRequirement().addList("admin"));
    }
}
