package com.pm.api_gateway.filter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
class RoleAuthorizationTest {
    @ParameterizedTest @CsvSource({"ADMIN,GET,true","ADMIN,POST,true","ADMIN,PUT,true","ADMIN,DELETE,true","USER,GET,true","USER,POST,false","USER,PUT,false","USER,DELETE,false","OTHER,GET,false"})
    void appliesVerifiedRoles(String role,String method,boolean allowed) {
        var factory=new JwtValidationGatewayFilterFactory(WebClient.builder().exchangeFunction(r->Mono.just(ClientResponse.create(HttpStatus.OK).header("X-Validated-Roles",role).build())),"http://auth.test",Duration.ofSeconds(1));
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.valueOf(method),"/patients").header("Authorization","Bearer valid").header("X-Validated-Roles","ADMIN"));
        var called=new AtomicBoolean();factory.apply(new JwtValidationGatewayFilterFactory.Config()).filter(exchange,e->{called.set(true);assertNull(e.getRequest().getHeaders().getFirst("X-Validated-Roles"));return Mono.empty();}).block();
        assertEquals(allowed,called.get());if(!allowed){assertEquals(HttpStatus.FORBIDDEN,exchange.getResponse().getStatusCode());}
    }
    @Test void successfulValidationWithoutRolesDoesNotGrantAccess() {
        var factory=new JwtValidationGatewayFilterFactory(WebClient.builder().exchangeFunction(r->Mono.just(ClientResponse.create(HttpStatus.OK).build())),"http://auth.test",Duration.ofSeconds(1));
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/patients").header("Authorization","Bearer valid").header("X-Validated-Roles","ADMIN"));
        factory.apply(new JwtValidationGatewayFilterFactory.Config()).filter(exchange,e->Mono.error(new AssertionError())).block();assertEquals(HttpStatus.FORBIDDEN,exchange.getResponse().getStatusCode());
    }
}
