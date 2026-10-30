package com.pm.patient_service.kafka;
import java.util.*;
import java.time.LocalDate;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.producer.ProducerRecord;
import com.pm.patient_service.entity.Patient;
import patient.events.PatientEvent;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class KafkaProducerTest {
    @Test @SuppressWarnings({"unchecked","rawtypes"}) void publishesStableIdentitySnapshotAndCorrelation() throws Exception {
        var template=mock(KafkaTemplate.class);when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var producer=new KafkaProducer(template);UUID id=UUID.randomUUID();
        var patient=new Patient(id,"Synthetic",123,"synthetic@example.com","Address",LocalDate.of(1990,1,1),LocalDate.of(2026,10,30));
        org.slf4j.MDC.put("correlationId","event-correlation-test");
        try {producer.sendEvent(patient);}finally{org.slf4j.MDC.remove("correlationId");}
        var capture=ArgumentCaptor.forClass(ProducerRecord.class);verify(template).send(capture.capture());
        var record=capture.getValue();assertEquals(id.toString(),record.key());assertEquals("patient-events",record.topic());
        var event=PatientEvent.parseFrom((byte[])record.value());assertEquals(id.toString(),event.getPatientId());assertEquals("PATIENT_CREATED",event.getEventType());assertEquals("Synthetic",event.getName());
        assertEquals("event-correlation-test",new String(record.headers().lastHeader("correlation-id").value(),java.nio.charset.StandardCharsets.US_ASCII));
    }
    @Test @SuppressWarnings({"unchecked","rawtypes"}) void failedConfirmationIsNotReportedAsDelivered() {
        var template=mock(KafkaTemplate.class);when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new TimeoutException("synthetic lost ack")));
        var producer=new KafkaProducer(template);var patient=new Patient(UUID.randomUUID(),"Synthetic",123,"synthetic@example.com","Address",LocalDate.of(1990,1,1),LocalDate.of(2026,10,30));
        assertThrows(IllegalStateException.class,()->producer.sendEvent(patient));verify(template,times(1)).send(any(ProducerRecord.class));
    }
}
