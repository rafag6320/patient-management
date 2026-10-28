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
            kafkaTemplate.send("patient-events", event.toByteArray()).get(10, TimeUnit.SECONDS);
            log.info("Kafka event sent to topic patient-events");
        } catch (Exception e) {
            log.error("Failed to send event to Kafka: {}", e.getMessage());
            throw new IllegalStateException("Kafka event could not be sent", e);
        }
    } 

}
