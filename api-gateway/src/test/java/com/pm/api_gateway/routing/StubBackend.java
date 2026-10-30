package com.pm.api_gateway.routing;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.test.context.DynamicPropertyRegistry;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Servicios HTTP efímeros; no contacta auth, patient ni bases del desarrollador. */
final class StubBackend implements AutoCloseable {
    final AtomicInteger patientCalls = new AtomicInteger();
    final AtomicInteger validationCalls = new AtomicInteger();
    final AtomicReference<String> patientUri = new AtomicReference<>();
    final AtomicReference<String> validationAuthorization = new AtomicReference<>();
    private final DisposableServer server = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> {
                String path = java.net.URI.create(request.uri()).getPath();
                response.header("Content-Type", "application/json");
                if (path.equals("/login") && request.method().name().equals("POST")) {
                    return request.receive().aggregate().asString().flatMap(body -> {
                        if (body.contains("slow-password")) { return Mono.never(); }
                        if (body.contains("disconnect-password")) {
                            response.withConnection(connection -> connection.dispose());
                            return Mono.empty();
                        }
                        if (body.contains("wrong-password")) {
                            return response.status(401).send().then();
                        }
                        return response.sendString(Mono.just("{\"token\":\"accepted-token\"}")).then();
                    });
                }
                if (path.equals("/validate")) {
                    validationCalls.incrementAndGet();
                    String authorization = request.requestHeaders().get("Authorization");
                    validationAuthorization.set(authorization);
                    if ("Bearer auth-slow".equals(authorization)) { return Mono.never(); }
                    if ("Bearer auth-disconnected".equals(authorization)) {
                        response.withConnection(connection -> connection.dispose());
                        return Mono.empty();
                    }
                    int status = "Bearer accepted-token".equals(authorization) ? 200
                            : "Bearer auth-error".equals(authorization) ? 503 : 401;
                    return response.header("X-Validated-Roles","ADMIN").status(status).send();
                }
                if (path.equals("/patients")) {
                    patientCalls.incrementAndGet();
                    patientUri.set(request.uri());
                    if(request.uri().contains("disconnect=true")) {
                        response.withConnection(connection->connection.dispose());return Mono.empty();
                    }
                    return response.sendString(Mono.just("[]"));
                }
                return response.status(404).send();
            }).bindNow();

    void configure(DynamicPropertyRegistry registry) {
        registry.add("auth.service.url", () -> "http://127.0.0.1:" + server.port());
        registry.add("analytics.service.url", () -> "http://127.0.0.1:" + server.port());
        registry.add("patient.service.url", () -> "http://127.0.0.1:" + server.port());
        registry.add("auth.service.validation-timeout", () -> "1s");
        registry.add("auth.service.proxy-timeout", () -> "1s");
    }

    @Override
    public void close() { server.disposeNow(); }
}
