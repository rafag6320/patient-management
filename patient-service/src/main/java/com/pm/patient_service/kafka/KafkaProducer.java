package com.pm.patient_service.kafka;

import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

import com.pm.patient_service.entity.Patient;

import ch.qos.logback.classic.Logger;
import patient.events.PatientEvent;

@Service
public class KafkaProducer {
    private final Logger log = (Logger) LoggerFactory.getLogger(KafkaProducer.class); 
    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    public KafkaProducer(KafkaTemplate<String, byte[]> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendEvent(Patient patient) {
        PatientEvent event = PatientEvent.newBuilder()
                .setPatientId(patient.getId().toString())
                .setName(patient.getName())
                .setEmail(patient.getEmail())
                .setEventType("PATIENT_CREATED")
                .build();
        try {
            var record=new org.apache.kafka.clients.producer.ProducerRecord<String,byte[]>("patient-events",patient.getId().toString(),event.toByteArray());
            String correlation=org.slf4j.MDC.get("correlationId");
            if(correlation!=null) { record.headers().add("correlation-id",correlation.getBytes(java.nio.charset.StandardCharsets.US_ASCII)); }
            kafkaTemplate.send(record).get(6, TimeUnit.SECONDS);
            log.info("Kafka event sent to topic patient-events");
        } catch (Exception e) {
            if(e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            log.warn("Kafka publication unconfirmed; durable work will be retried");
            throw new IllegalStateException("Kafka event could not be sent", e);
        }
    } 

}
