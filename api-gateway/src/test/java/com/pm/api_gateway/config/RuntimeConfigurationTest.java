package com.pm.api_gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigurationTest {
    private MockEnvironment valid() {
        var environment=new MockEnvironment();
        environment.setProperty("server.port","4000");
        environment.setProperty("auth.service.url","http://localhost:4005");
        environment.setProperty("patient.service.url","http://localhost:4000");
        environment.setProperty("auth.service.validation-timeout","2s");
        environment.setProperty("auth.service.proxy-timeout","2s");
        environment.setProperty("analytics.service.url","http://localhost:4002");
        return environment;
    }
    @Test void acceptsCompleteProductionSettings() {
        var environment=valid(); environment.setActiveProfiles("prod");
        assertDoesNotThrow(()->RuntimeConfiguration.validate(environment));
    }
    @Test void failsForEachMissingRequiredKeyWithoutDisclosingValues() {
        for (String key:new String[]{"server.port","auth.service.url","patient.service.url","auth.service.validation-timeout","auth.service.proxy-timeout"}) {
            var environment=valid(); environment.setProperty(key,"");
            String message=assertThrows(IllegalStateException.class,()->RuntimeConfiguration.validate(environment)).getMessage();
            assertTrue(message.contains(key)); assertFalse(message.contains("unit-password"));
        }
    }
    @Test void forbidsMixedProductionAndDevelopmentProfiles() {
        for (String profile:new String[]{"local","docker"}) {
            var environment=valid(); environment.setActiveProfiles("prod",profile);
            assertThrows(IllegalStateException.class,()->RuntimeConfiguration.validate(environment));
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
    @Test void rejectsInvalidDestinationsAndTimeouts() {
        for (String value:new String[]{"not-a-url","ftp://localhost","http://localhost:70000","http://user:pass@localhost"}) {
            var environment=valid().withProperty("auth.service.url",value);
            assertThrows(IllegalStateException.class,()->RuntimeConfiguration.validate(environment));
        }
        for (String value:new String[]{"0s","-1s","invalid"}) {
            var environment=valid().withProperty("auth.service.validation-timeout",value);
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
}
