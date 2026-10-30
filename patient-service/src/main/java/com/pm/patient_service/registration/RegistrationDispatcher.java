package com.pm.patient_service.registration;

import java.time.Instant;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="registration.dispatcher.enabled",havingValue="true",matchIfMissing=true)
public class RegistrationDispatcher {
    private final RegistrationStore store;
    private final RegistrationCoordinator coordinator;
    public RegistrationDispatcher(RegistrationStore store,RegistrationCoordinator coordinator) { this.store=store;this.coordinator=coordinator; }
    @Scheduled(fixedDelayString="${registration.dispatcher.delay-ms:1000}",initialDelayString="${registration.dispatcher.delay-ms:1000}")
    public void run() {
        try {
            for(var id:store.due(Instant.now(),10)) {
                try { coordinator.process(id,true); }
                catch(RuntimeException ex) { LoggerFactory.getLogger(getClass()).warn("Registration recovery deferred: storage unavailable"); }
            }
        } catch(RuntimeException ex) { LoggerFactory.getLogger(getClass()).warn("Registration scan deferred: storage unavailable"); }
    }
    @Configuration(proxyBeanMethods=false) @EnableScheduling
    static class Scheduling {}
}
