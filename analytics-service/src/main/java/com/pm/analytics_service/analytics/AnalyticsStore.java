package com.pm.analytics_service.analytics;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.security.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import io.micrometer.core.instrument.MeterRegistry;

@Repository
public class AnalyticsStore {
    private final JdbcTemplate sql;
    private final MeterRegistry metrics;
    public AnalyticsStore(JdbcTemplate sql,MeterRegistry metrics) { this.sql=sql;this.metrics=metrics; }
    public void record(UUID patient) {
        try {
            sql.update("INSERT INTO processed_patient_event (event_type,patient_id,processed_at) VALUES ('PATIENT_CREATED',?,?)",patient,Timestamp.from(Instant.now()));
            metrics.counter("analytics.events.processed").increment();
        } catch(DuplicateKeyException duplicate) { metrics.counter("analytics.events.duplicates").increment(); }
    }
    public long patientsCreated() { return sql.queryForObject("SELECT count(*) FROM processed_patient_event WHERE event_type='PATIENT_CREATED'",Long.class); }
    public long quarantined() { return sql.queryForObject("SELECT count(*) FROM event_quarantine",Long.class); }
    public void quarantine(String topic,int partition,long offset,String reason,byte[] payload) {
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
            sql.update("INSERT INTO event_quarantine (topic,partition_id,offset_id,reason,payload_hash,created_at) VALUES (?,?,?,?,?,?)",
                topic,partition,offset,reason,hash,Timestamp.from(Instant.now()));
            metrics.counter("analytics.events.quarantined").increment();
        } catch(DuplicateKeyException duplicate) { /* persisted before an offset ack was lost */ }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
