package com.pm.api_gateway.filter;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Acota el contacto con auth sin alterar sus respuestas HTTP normales. */
@Component
public class AuthProxyGatewayFilterFactory
        extends AbstractGatewayFilterFactory<AuthProxyGatewayFilterFactory.Config> {
    private final Duration timeout;

    public AuthProxyGatewayFilterFactory(@Value("${auth.service.proxy-timeout:2s}") Duration timeout) {
        super(Config.class);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("auth.service.proxy-timeout must be positive");
        }
        this.timeout = timeout;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> chain.filter(exchange).timeout(timeout).onErrorResume(error -> {
            if (exchange.getResponse().isCommitted()) { return Mono.error(error); }
            exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
            return exchange.getResponse().setComplete();
        });
    }

    public static class Config { }
}
