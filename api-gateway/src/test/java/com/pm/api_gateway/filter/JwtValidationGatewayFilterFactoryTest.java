package com.pm.api_gateway.filter;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.*;

class JwtValidationGatewayFilterFactoryTest {
    private static final Duration LIMIT = Duration.ofSeconds(2);

    private JwtValidationGatewayFilterFactory factory(ExchangeFunction auth) {
        return new JwtValidationGatewayFilterFactory(WebClient.builder().exchangeFunction(auth),
                "http://auth.test", LIMIT);
    }

    private MockServerWebExchange request(String authorization) {
        var request = MockServerHttpRequest.get("/api/patients");
        if (authorization != null) { request.header("Authorization", authorization); }
        return MockServerWebExchange.from(request);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Basic anything", "Bearer ", "Bearer    ", "bearer token"})
    void rejectsMissingOrMalformedBearerWithoutContactingAuth(String authorization) {
        AtomicInteger calls = new AtomicInteger();
        var gateway = factory(req -> { calls.incrementAndGet(); return Mono.error(new AssertionError()); });
        var exchange = request(authorization);
        StepVerifier.create(gateway.apply(new JwtValidationGatewayFilterFactory.Config())
                .filter(exchange, ignored -> Mono.error(new AssertionError()))).verifyComplete();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429, 302, 500, 503})
    void refusesPatientAccessForAuthFailures(int authStatus) {
        var gateway = factory(req -> Mono.just(ClientResponse.create(HttpStatus.valueOf(authStatus)).build()));
        var exchange = request("Bearer rejected-token");
        StepVerifier.create(gateway.apply(new JwtValidationGatewayFilterFactory.Config())
                .filter(exchange, ignored -> Mono.error(new AssertionError()))).verifyComplete();
        assertEquals(authStatus >= 400 && authStatus < 500 ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_GATEWAY,
                exchange.getResponse().getStatusCode());
    }

    @Test
    void forwardsAuthorizationToValidateThenCallsPatientOnce() {
        AtomicInteger calls = new AtomicInteger();
        var gateway = factory(req -> {
            assertEquals("/validate", req.url().getPath());
            assertEquals("Bearer accepted-token", req.headers().getFirst("Authorization"));
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("X-Validated-Roles","ADMIN").build());
        });
        StepVerifier.create(gateway.apply(new JwtValidationGatewayFilterFactory.Config())
                .filter(request("Bearer accepted-token"), ignored -> { calls.incrementAndGet(); return Mono.empty(); }))
                .verifyComplete();
        assertEquals(1, calls.get());
    }

    @Test
    void connectionFailureReturnsBadGatewayWithoutPatientAccess() {
        var gateway = factory(req -> Mono.error(new ConnectException("test connection refused")));
        var exchange = request("Bearer token");
        StepVerifier.create(gateway.apply(new JwtValidationGatewayFilterFactory.Config())
                .filter(exchange, ignored -> Mono.error(new AssertionError()))).verifyComplete();
        assertEquals(HttpStatus.BAD_GATEWAY, exchange.getResponse().getStatusCode());
    }

    @Test
    void timeoutCancelsValidationAtConfiguredLimit() {
        AtomicBoolean cancelled = new AtomicBoolean();
        var gateway = factory(req -> Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true)));
        var exchange = request("Bearer token");
        StepVerifier.withVirtualTime(() -> gateway.apply(new JwtValidationGatewayFilterFactory.Config())
                .filter(exchange, ignored -> Mono.error(new AssertionError())))
                .expectSubscription().expectNoEvent(LIMIT.minusMillis(1))
                .thenAwait(Duration.ofMillis(1)).verifyComplete();
        assertTrue(cancelled.get());
        assertEquals(HttpStatus.BAD_GATEWAY, exchange.getResponse().getStatusCode());
    }

    @Test
    void patientErrorsAndLatencyAreNotConvertedToAuthFailures() {
        var gateway = factory(req -> Mono.just(ClientResponse.create(HttpStatus.OK).header("X-Validated-Roles","ADMIN").build()));
        RuntimeException patientFailure = new RuntimeException("patient test failure");
        StepVerifier.withVirtualTime(() -> gateway.apply(new JwtValidationGatewayFilterFactory.Config())
                .filter(request("Bearer token"), ignored -> Mono.delay(LIMIT.multipliedBy(2))
                        .then(Mono.error(patientFailure))))
                .thenAwait(LIMIT.multipliedBy(2)).expectErrorMatches(error -> error == patientFailure).verify();
    }

    @Test
    void rejectsNonPositiveTimeoutAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new JwtValidationGatewayFilterFactory(
                WebClient.builder(), "http://auth.test", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new JwtValidationGatewayFilterFactory(
                WebClient.builder(), "http://auth.test", Duration.ofMillis(-1)));
    }
}
