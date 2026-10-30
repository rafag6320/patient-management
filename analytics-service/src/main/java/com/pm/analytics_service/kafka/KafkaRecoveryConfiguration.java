package com.pm.analytics_service.kafka;
import org.springframework.context.annotation.*;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
@Configuration(proxyBeanMethods=false)
public class KafkaRecoveryConfiguration {
    @Bean DefaultErrorHandler kafkaErrorHandler() {
        // Never acknowledge/discard an event while its SQL result is unavailable.
        return new DefaultErrorHandler(new FixedBackOff(1000L,FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
