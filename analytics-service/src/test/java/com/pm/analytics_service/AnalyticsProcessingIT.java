package com.pm.analytics_service;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import com.pm.analytics_service.analytics.AnalyticsStore;
import com.pm.analytics_service.kafka.KafkaConsumer;
import patient.events.PatientEvent;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class AnalyticsProcessingIT {
    @Autowired AnalyticsStore store;
    @Autowired KafkaConsumer consumer;
    @Autowired JdbcTemplate sql;
    @BeforeEach void clean() {sql.update("DELETE FROM processed_patient_event");sql.update("DELETE FROM event_quarantine");}
    private byte[] event(UUID id) {return PatientEvent.newBuilder().setEventType("PATIENT_CREATED").setPatientId(id.toString()).setName("Synthetic").setEmail("synthetic@example.com").build().toByteArray();}
    private ConsumerRecord<String,byte[]> record(long offset,byte[] body) {return new ConsumerRecord<>("patient-events",0,offset,null,body);}
    @Test void malformedCorrelationHeadersDoNotPoisonAValidEventOrLeakMdc() {
        var record=record(10,event(UUID.randomUUID()));record.headers().add("correlation-id",(byte[])null);
        consumer.consumeEvent(record);assertEquals(1,store.patientsCreated());assertNull(org.slf4j.MDC.get("correlationId"));
        record=record(11,event(UUID.randomUUID()));record.headers().add("correlation-id",new byte[100]);consumer.consumeEvent(record);assertEquals(2,store.patientsCreated());
    }
    @Test void duplicateMessagesDoNotIncreaseHistoricalCount() {
        UUID id=UUID.randomUUID();consumer.consumeEvent(record(1,event(id)));consumer.consumeEvent(record(2,event(id)));
        assertEquals(1,store.patientsCreated());consumer.consumeEvent(record(3,event(UUID.randomUUID())));assertEquals(2,store.patientsCreated());
    }
    @Test void concurrentDeliveriesHaveOneDurableEffect() throws Exception {
        UUID id=UUID.randomUUID();var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<?>>();for(int i=0;i<4;i++) {futures.add(executor.submit(()->{start.await();consumer.consumeEvent(record(1,event(id)));return null;}));}
            start.countDown();for(var f:futures) {f.get(10,TimeUnit.SECONDS);}
        }
        assertEquals(1,store.patientsCreated());
    }
    @Test void invalidMessagesAreDurablyQuarantinedWithoutPersonalPayload() {
        consumer.consumeEvent(record(1,new byte[]{(byte)255}));consumer.consumeEvent(record(1,new byte[]{(byte)255}));
        consumer.consumeEvent(record(2,null));consumer.consumeEvent(record(3,PatientEvent.getDefaultInstance().toByteArray()));
        consumer.consumeEvent(record(4,PatientEvent.newBuilder().setEventType("PATIENT_CREATED").setPatientId("bad").build().toByteArray()));
        assertEquals(4,store.quarantined());assertEquals(0,store.patientsCreated());
        assertEquals(64,sql.queryForObject("SELECT payload_hash FROM event_quarantine WHERE offset_id=1",String.class).length());
        var columns=sql.queryForList("SELECT column_name FROM information_schema.columns WHERE lower(table_name)='event_quarantine'",String.class);
        assertFalse(columns.stream().anyMatch(c->c.equalsIgnoreCase("payload")));assertFalse(columns.stream().anyMatch(c->c.equalsIgnoreCase("email")));
    }
    @Test void sqlFailureIsPropagatedForRedeliveryThenRecovered() {
        byte[] message=event(UUID.randomUUID());sql.execute("ALTER TABLE processed_patient_event RENAME TO unavailable_result");
        try {assertThrows(org.springframework.dao.DataAccessException.class,()->consumer.consumeEvent(record(1,message)));}
        finally {sql.execute("ALTER TABLE unavailable_result RENAME TO processed_patient_event");}
        consumer.consumeEvent(record(1,message));consumer.consumeEvent(record(1,message));assertEquals(1,store.patientsCreated());
    }
}
