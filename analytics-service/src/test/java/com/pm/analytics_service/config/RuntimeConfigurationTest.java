package com.pm.analytics_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigurationTest {
    private MockEnvironment valid() {
        var environment=new MockEnvironment();
        environment.setProperty("server.port","4000");
        environment.setProperty("spring.kafka.bootstrap-servers","localhost:9094");
        environment.setProperty("spring.datasource.url","jdbc:postgresql://localhost:5432/unit");
        environment.setProperty("spring.datasource.username","unit");
        environment.setProperty("spring.datasource.password","unit-password");
        return environment;
    }
    @Test void awsRequiresSafeInjectedKafkaCredentialsWithoutDisclosingValues() {
        var environment=valid();environment.setActiveProfiles("prod","aws");
        var ex=assertThrows(IllegalStateException.class,()->RuntimeConfiguration.validate(environment));assertTrue(ex.getMessage().contains("KAFKA_PASSWORD"));
        environment.setProperty("KAFKA_USERNAME","publisher");environment.setProperty("KAFKA_PASSWORD","synthetic-safe-password");assertDoesNotThrow(()->RuntimeConfiguration.validate(environment));
        environment.setProperty("KAFKA_PASSWORD","private\"invalid");ex=assertThrows(IllegalStateException.class,()->RuntimeConfiguration.validate(environment));assertFalse(ex.getMessage().contains("private"));
        environment.setActiveProfiles("aws","docker");assertThrows(IllegalStateException.class,()->RuntimeConfiguration.validate(environment));
    }
    @Test void acceptsCompleteProductionSettings() {
        var environment=valid(); environment.setActiveProfiles("prod");
        assertDoesNotThrow(()->RuntimeConfiguration.validate(environment));
    }
    @Test void failsForEachMissingRequiredKeyWithoutDisclosingValues() {
        for (String key:new String[]{"server.port","spring.kafka.bootstrap-servers"}) {
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
    @Test void rejectsMalformedKafkaBrokers() {
        for (String value:new String[]{"localhost","http://localhost:9092","localhost:70000","localhost:9092,"}) {
            var environment=valid().withProperty("spring.kafka.bootstrap-servers",value);
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
