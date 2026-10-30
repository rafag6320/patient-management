package com.pm.auth_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigurationTest {
    private MockEnvironment valid() {
        var environment=new MockEnvironment();
        environment.setProperty("server.port","4000");
        environment.setProperty("spring.datasource.url","jdbc:postgresql://localhost:5432/unit");
        environment.setProperty("spring.datasource.username","unit");
        environment.setProperty("spring.datasource.password","unit-password");
        environment.setProperty("jwt.secret","unit-test-key-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        environment.setProperty("spring.sql.init.mode","never");
        return environment;
    }
    @Test void acceptsCompleteProductionSettings() {
        var environment=valid(); environment.setActiveProfiles("prod");
        assertDoesNotThrow(()->RuntimeConfiguration.validate(environment));
    }
    @Test void failsForEachMissingRequiredKeyWithoutDisclosingValues() {
        for (String key:new String[]{"server.port","spring.datasource.url","spring.datasource.username","spring.datasource.password","jwt.secret","spring.sql.init.mode"}) {
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
    @Test void rejectsH2EvenWhenExplicitlyConfiguredOutsideTests() {
        var environment=valid().withProperty("spring.datasource.url","jdbc:h2:mem:accidental");
        assertThrows(IllegalStateException.class,()->RuntimeConfiguration.validate(environment));
    }
    @Test void rejectsProductionCombinedWithTest() {
        var environment=valid(); environment.setActiveProfiles("prod","test");
        assertThrows(IllegalStateException.class,()->RuntimeConfiguration.validate(environment));
    }
    @Test void forbidsSeedOutsideDevelopmentAndChecksJwtKeyLength() {
        var environment=valid().withProperty("spring.sql.init.mode","always");
        assertThrows(IllegalStateException.class,()->RuntimeConfiguration.validate(environment));
        var weak=valid().withProperty("jwt.secret","short");
        assertThrows(IllegalStateException.class,()->RuntimeConfiguration.validate(weak));
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
