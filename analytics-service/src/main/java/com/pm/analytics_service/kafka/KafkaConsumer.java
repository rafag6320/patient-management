package com.pm.analytics_service.kafka;

import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import com.google.protobuf.InvalidProtocolBufferException;
import com.pm.analytics_service.analytics.AnalyticsStore;
import patient.events.PatientEvent;

@Service
public class KafkaConsumer {
    private final AnalyticsStore store;
    public KafkaConsumer(AnalyticsStore store) { this.store=store; }
    @KafkaListener(topics="patient-events",groupId="analytics-service")
    public void consumeEvent(ConsumerRecord<String,byte[]> record) {
        var header=record.headers().lastHeader("correlation-id");
        String id=header==null || header.value()==null || header.value().length>64?null:new String(header.value(),java.nio.charset.StandardCharsets.US_ASCII);
        if(id==null || !id.matches("[A-Za-z0-9._:-]{1,64}")){id=UUID.randomUUID().toString();}
        String previous=org.slf4j.MDC.get("correlationId");org.slf4j.MDC.put("correlationId",id);
        try {processRecord(record);} finally {if(previous==null){org.slf4j.MDC.remove("correlationId");}else{org.slf4j.MDC.put("correlationId",previous);}}
    }
    private void processRecord(ConsumerRecord<String,byte[]> record) {
        if(record.value()==null) { quarantine(record,"NULL_PAYLOAD");return; }
        PatientEvent event;
        try { event=PatientEvent.parseFrom(record.value()); }
        catch(InvalidProtocolBufferException ex) { quarantine(record,"MALFORMED_PROTOBUF");return; }
        if(!"PATIENT_CREATED".equals(event.getEventType())) { quarantine(record,"UNSUPPORTED_TYPE");return; }
        UUID id;
        try {
            id=UUID.fromString(event.getPatientId());
            if(!id.toString().equalsIgnoreCase(event.getPatientId()) || event.getName().isBlank() || event.getEmail().isBlank()) {
                quarantine(record,"INVALID_EVENT");return;
            }
        } catch(IllegalArgumentException ex) { quarantine(record,"INVALID_PATIENT_ID");return; }
        store.record(id); // SQL commit precedes listener return/offset commit; duplicates are harmless.
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Patient creation event processed");
    }
    private void quarantine(ConsumerRecord<String,byte[]> record,String reason) {
        store.quarantine(record.topic(),record.partition(),record.offset(),reason,record.value()==null?new byte[0]:record.value());
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Patient event quarantined: {}",reason);
    }
}
