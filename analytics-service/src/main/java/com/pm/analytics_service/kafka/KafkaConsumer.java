package com.pm.analytics_service.kafka;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.google.protobuf.InvalidProtocolBufferException;

import patient.events.PatientEvent;

@Service
public class KafkaConsumer {
    private static final Logger log = LoggerFactory.getLogger(KafkaConsumer.class);
    @KafkaListener(topics = "patient-events", groupId = "analytics-service")
    public void consumeEvent(byte[] message) {
        try {
            PatientEvent patientEvent = PatientEvent.parseFrom(message);
            log.info("Received PatientEvent from Kafka: {}", patientEvent);
            // Process the patientEvent as needed
            
        } catch (InvalidProtocolBufferException e) {
            log.error("Failed to parse PatientEvent from Kafka message {}", e);
            e.printStackTrace();
        }
    }
}
