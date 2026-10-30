package com.pm.api_gateway.filter;

import java.net.ConnectException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.*;

class AuthProxyGatewayFilterFactoryTest {
    private final AuthProxyGatewayFilterFactory factory = new AuthProxyGatewayFilterFactory(Duration.ofSeconds(2));

    @Test
    void boundsSilentAuthProxy() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login"));
        StepVerifier.withVirtualTime(() -> factory.apply(new AuthProxyGatewayFilterFactory.Config())
                .filter(exchange, ignored -> Mono.never()))
                .expectSubscription().expectNoEvent(Duration.ofMillis(1999))
                .thenAwait(Duration.ofMillis(1)).verifyComplete();
        assertEquals(HttpStatus.BAD_GATEWAY, exchange.getResponse().getStatusCode());
    }

    @Test
    void mapsConnectionFailureWithoutForwardingAnException() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login"));
        StepVerifier.create(factory.apply(new AuthProxyGatewayFilterFactory.Config())
                .filter(exchange, ignored -> Mono.error(new ConnectException("test connection refused"))))
                .verifyComplete();
        assertEquals(HttpStatus.BAD_GATEWAY, exchange.getResponse().getStatusCode());
    }

    @Test
    void preservesAuthHttpResponse() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login"));
        StepVerifier.create(factory.apply(new AuthProxyGatewayFilterFactory.Config()).filter(exchange, ignored -> {
            exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return exchange.getResponse().setComplete();
        })).verifyComplete();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
    }

    @Test
    void doesNotRewriteAlreadyCommittedResponse() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login"));
        RuntimeException failure = new RuntimeException("test failure after headers");
        StepVerifier.create(factory.apply(new AuthProxyGatewayFilterFactory.Config()).filter(exchange, ignored ->
                exchange.getResponse().setComplete().then(Mono.error(failure))))
                .expectErrorMatches(error -> error == failure).verify();
        assertNotEquals(HttpStatus.BAD_GATEWAY, exchange.getResponse().getStatusCode());
    }

    @Test
    void requiresPositiveTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new AuthProxyGatewayFilterFactory(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new AuthProxyGatewayFilterFactory(Duration.ofSeconds(-1)));
    }
}
