package com.pm.api_gateway.routing;

import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles({"test", "prod"})
class GatewayProdRoutingIT extends GatewayRoutingChecks {
    private static final StubBackend BACKEND = new StubBackend();
    @DynamicPropertySource
    static void addresses(DynamicPropertyRegistry registry) { BACKEND.configure(registry); }
    @AfterAll
    static void stopBackend() { BACKEND.close(); }
    @Override
    StubBackend backend() { return BACKEND; }
}
