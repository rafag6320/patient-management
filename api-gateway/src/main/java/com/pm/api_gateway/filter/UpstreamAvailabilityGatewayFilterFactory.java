package com.pm.api_gateway.filter;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
/** No retries: an HTTP timeout may follow a committed patient registration. */
@Component
public class UpstreamAvailabilityGatewayFilterFactory extends AbstractGatewayFilterFactory<UpstreamAvailabilityGatewayFilterFactory.Config> {
    private final Duration timeout;
    public UpstreamAvailabilityGatewayFilterFactory(@Value("${upstream.response-timeout:15s}") Duration timeout) {
        super(Config.class);if(timeout.isZero()||timeout.isNegative()){throw new IllegalArgumentException("upstream.response-timeout must be positive");}this.timeout=timeout;
    }
    @Override public GatewayFilter apply(Config config) {
        return (exchange,chain)->chain.filter(exchange).timeout(timeout).onErrorResume(error->{
            if(exchange.getResponse().isCommitted()){return Mono.error(error);}
            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] body="{\"error\":\"Upstream unavailable; registration may exist. Retry with the same Idempotency-Key\"}".getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        });
    }
    public static class Config {}
}
