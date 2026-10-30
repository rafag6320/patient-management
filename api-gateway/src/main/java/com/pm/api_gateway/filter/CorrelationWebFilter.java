package com.pm.api_gateway.filter;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationWebFilter implements WebFilter {
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
        String id=exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");
        if(id==null || !id.matches("[A-Za-z0-9._:-]{1,64}")) { id=UUID.randomUUID().toString(); }
        String correlation=id;exchange.getResponse().getHeaders().set("X-Correlation-Id",id);
        return chain.filter(exchange.mutate().request(r->r.headers(h->{h.set("X-Correlation-Id",correlation);h.remove("X-Validated-Roles");})).build());
    }
}
