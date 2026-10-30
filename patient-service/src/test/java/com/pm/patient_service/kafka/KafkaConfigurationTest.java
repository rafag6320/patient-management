package com.pm.patient_service.kafka;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.kafka.core.ProducerFactory;
import static org.junit.jupiter.api.Assertions.*;
class KafkaConfigurationTest {
    private ApplicationContextRunner configured(boolean aws) throws Exception {
        var props=PropertiesLoaderUtils.loadAllProperties("application.properties");
        if(aws){props.putAll(PropertiesLoaderUtils.loadAllProperties("application-aws.properties"));}
        var values=new ArrayList<String>();props.forEach((k,v)->values.add(k+"="+v));
        values.add("spring.kafka.bootstrap-servers=127.0.0.1:1");values.add("spring.kafka.admin.auto-create=false");
        values.add("KAFKA_USERNAME=synthetic-publisher");values.add("KAFKA_PASSWORD=synthetic-safe-password");
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class)).withPropertyValues(values.toArray(String[]::new));
    }
    @Test void effectiveProducerHonorsAcknowledgementAndTimeBounds() throws Exception {
        configured(false).run(context->{
            assertNull(context.getStartupFailure());var props=context.getBean(ProducerFactory.class).getConfigurationProperties();
            assertEquals("all",props.get("acks"));assertEquals("true",String.valueOf(props.get("enable.idempotence")));
            assertEquals("1000",String.valueOf(props.get("max.block.ms")));assertEquals("1000",String.valueOf(props.get("request.timeout.ms")));
            assertEquals("5000",String.valueOf(props.get("delivery.timeout.ms")));
        });
    }
    @Test void effectiveAwsProducerUsesTlsAndInjectedScramCredentials() throws Exception {
        configured(true).run(context->{
            assertNull(context.getStartupFailure());var props=context.getBean(ProducerFactory.class).getConfigurationProperties();
            assertEquals("SASL_SSL",props.get("security.protocol"));assertEquals("SCRAM-SHA-512",props.get("sasl.mechanism"));
            String jaas=String.valueOf(props.get("sasl.jaas.config"));assertTrue(jaas.contains("username=\"synthetic-publisher\""));assertTrue(jaas.contains("password=\"synthetic-safe-password\""));
        });
    }
}
