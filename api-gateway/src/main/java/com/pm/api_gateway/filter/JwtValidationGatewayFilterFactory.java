package com.pm.api_gateway.filter;

import java.time.Duration;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

@Component
public class JwtValidationGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JwtValidationGatewayFilterFactory.Config> {

    private final WebClient webClient;
    private final Duration validationTimeout;

    public JwtValidationGatewayFilterFactory(WebClient.Builder webClientBuilder,
            @Value("${auth.service.url}") String authServiceUrl,
            @Value("${auth.service.validation-timeout:2s}") Duration validationTimeout) {
        super(Config.class);
        if (validationTimeout.isZero() || validationTimeout.isNegative()) {
            throw new IllegalArgumentException("auth.service.validation-timeout must be positive");
        }
        this.webClient = webClientBuilder.baseUrl(authServiceUrl).build();
        this.validationTimeout = validationTimeout;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String token = exchange.getRequest().getHeaders().getFirst("Authorization");

            if (token == null || !token.startsWith("Bearer ") || token.substring(7).isBlank()) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            // El límite y la recuperación cubren sólo auth, nunca la llamada a patient.
            return webClient.get()
                    .uri("/validate")
                    .header("Authorization", token)
                    .header("X-Correlation-Id", exchange.getRequest().getHeaders().getFirst("X-Correlation-Id")==null?"gateway":exchange.getRequest().getHeaders().getFirst("X-Correlation-Id"))
                    .exchangeToMono(response -> {
                        HttpStatus result = response.statusCode().is2xxSuccessful() ? HttpStatus.OK
                                : response.statusCode().is4xxClientError() ? HttpStatus.UNAUTHORIZED
                                : HttpStatus.BAD_GATEWAY;
                        if(result==HttpStatus.OK) {
                            String header=response.headers().asHttpHeaders().getFirst("X-Validated-Roles");
                            var roles=header==null?java.util.Set.<String>of():new java.util.HashSet<>(java.util.Arrays.asList(header.split(",")));
                            boolean read=org.springframework.http.HttpMethod.GET.equals(exchange.getRequest().getMethod());
                            if(!roles.contains("ADMIN") && !(read && roles.contains("USER"))) { result=HttpStatus.FORBIDDEN; }
                        }
                        return response.releaseBody().thenReturn(result);
                    })
                    .timeout(validationTimeout, Mono.just(HttpStatus.BAD_GATEWAY))
                    .onErrorReturn(HttpStatus.BAD_GATEWAY)
                    .flatMap(result -> {
                        if (result == HttpStatus.OK) {
                            return chain.filter(exchange.mutate().request(request->request.headers(headers->headers.remove("X-Validated-Roles"))).build());
                        }
                        exchange.getResponse().setStatusCode(result);
                        return exchange.getResponse().setComplete();
                    });
        };
    }

    public static class Config {
    }
}
