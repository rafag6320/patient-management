package com.pm.api_gateway.config;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/** Gateway routes are filters rather than annotated business controllers. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean
    OpenApiCustomizer publicApi() throws IOException {
        byte[] specification;
        try (var source = new ClassPathResource("static/openapi/gateway.json").getInputStream()) {
            specification = source.readAllBytes();
        }
        // Customize the generated document after discovery. Springdoc's initial
        // bean clone uses a plain mapper that cannot copy typed map schemas.
        return api -> {
            try {
                var catalog = Json.mapper().readValue(specification, OpenAPI.class);
                api.setOpenapi(catalog.getOpenapi());
                api.setInfo(catalog.getInfo());
                api.setServers(catalog.getServers());
                api.setTags(catalog.getTags());
                api.setPaths(catalog.getPaths());
                api.setComponents(catalog.getComponents());
            } catch (IOException ex) {
                throw new UncheckedIOException("Invalid public OpenAPI catalog", ex);
            }
        };
    }
}
