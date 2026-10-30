package com.pm.billing_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigurationTest {
    private MockEnvironment valid() {
        var environment=new MockEnvironment();
        environment.setProperty("server.port","4000");
        environment.setProperty("grpc.server.port","9001");
        environment.setProperty("spring.datasource.url","jdbc:postgresql://localhost:5432/unit");
        environment.setProperty("spring.datasource.username","unit");
        environment.setProperty("spring.datasource.password","unit-password");
        return environment;
    }
    @Test void acceptsCompleteProductionSettings() {
        var environment=valid(); environment.setActiveProfiles("prod");
        assertDoesNotThrow(()->RuntimeConfiguration.validate(environment));
    }
    @Test void failsForEachMissingRequiredKeyWithoutDisclosingValues() {
        for (String key:new String[]{"server.port","grpc.server.port","spring.datasource.url","spring.datasource.username","spring.datasource.password"}) {
            var environment=valid(); environment.setProperty(key,"");
            String message=assertThrows(IllegalStateException.class,()->RuntimeConfiguration.validate(environment)).getMessage();
            assertTrue(message.contains(key)); assertFalse(message.contains("unit-password"));
        }
    }
    @Test void forbidsMixedProductionAndDevelopmentProfiles() {
        for (String profile:new String[]{"local","docker"}) {
            var environment=valid(); environment.setActiveProfiles("prod",profile);
            assertThrows(IllegalStateException.class,()->RuntimeConfiguration.validate(environment));
            environment.setActiveProfiles("aws",profile);assertThrows(IllegalStateException.class,()->RuntimeConfiguration.validate(environment));
        }
    }
    @Test void permitsIsolatedTestProfile() {
        var environment=new MockEnvironment(); environment.setActiveProfiles("test");
        assertDoesNotThrow(()->RuntimeConfiguration.validate(environment));
    }
    @Test void rejectsInvalidPort() {
        for (String value:new String[]{"-1","65536","invalid"}) {
            var environment=valid().withProperty("server.port",value);
            assertThrows(IllegalStateException.class,()->RuntimeConfiguration.validate(environment));
        }
    }
    @Test void rejectsConfigurationBeforeInstantiatingNetworkDependencies() {
        var created=new java.util.concurrent.atomic.AtomicBoolean();
        try (var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "invalid-test",java.util.Map.of("server.port","invalid")));
            context.register(RuntimeConfiguration.class);
            context.registerBean("networkDependency",Object.class,()->{ created.set(true); return new Object(); });
            assertThrows(IllegalStateException.class,context::refresh);
            assertFalse(created.get());
        }
    }
    @Test void forbidsH2AndMixedProductionTest() {
        var h2=valid().withProperty("spring.datasource.url","jdbc:h2:mem:forbidden");
        assertThrows(IllegalStateException.class,()->RuntimeConfiguration.validate(h2));
        var mixed=valid(); mixed.setActiveProfiles("prod","test");
        assertThrows(IllegalStateException.class,()->RuntimeConfiguration.validate(mixed));
    }
}
