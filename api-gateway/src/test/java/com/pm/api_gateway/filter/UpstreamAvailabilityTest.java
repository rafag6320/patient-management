package com.pm.api_gateway.filter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
class UpstreamAvailabilityTest {
    @Test void connectionFailureIs503AndNeverRetriesPost() {
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/patients"));var calls=new AtomicInteger();
        new UpstreamAvailabilityGatewayFilterFactory(Duration.ofSeconds(1)).apply(new UpstreamAvailabilityGatewayFilterFactory.Config()).filter(exchange,e->{calls.incrementAndGet();return Mono.error(new java.net.ConnectException("private transport details"));}).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,exchange.getResponse().getStatusCode());assertEquals(1,calls.get());assertFalse(exchange.getResponse().getBodyAsString().block().contains("private"));
    }
    @Test void responseTimeoutIsBoundedAndCommittedResponsesAreNotRewritten() {
        var factory=new UpstreamAvailabilityGatewayFilterFactory(Duration.ofMillis(50));var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/patients"));
        factory.apply(new UpstreamAvailabilityGatewayFilterFactory.Config()).filter(exchange,e->Mono.never()).block(Duration.ofSeconds(1));assertEquals(HttpStatus.SERVICE_UNAVAILABLE,exchange.getResponse().getStatusCode());
        var committed=MockServerWebExchange.from(MockServerHttpRequest.get("/patients"));committed.getResponse().setStatusCode(HttpStatus.OK);committed.getResponse().setComplete().block();
        assertThrows(RuntimeException.class,()->factory.apply(new UpstreamAvailabilityGatewayFilterFactory.Config()).filter(committed,e->Mono.error(new IllegalStateException())).block());assertEquals(HttpStatus.OK,committed.getResponse().getStatusCode());
    }
}
