package com.pm.patient_service.registration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import com.pm.patient_service.dto.PatientRequestDTO;
import com.pm.patient_service.mapper.PatientMapper;
import com.pm.patient_service.repository.PatientRepository;
import com.pm.patient_service.exception.PatientAlreadyExistsException;

@Repository
public class RegistrationStore {
    private final JdbcTemplate sql;
    private final PatientRepository patients;
    private final TransactionTemplate tx;
    public RegistrationStore(JdbcTemplate sql, PatientRepository patients, PlatformTransactionManager manager) {
        this.sql=sql; this.patients=patients; this.tx=new TransactionTemplate(manager);
    }
    private static final RowMapper<RegistrationWork> MAPPER=(r,n)->new RegistrationWork(
        r.getObject("patient_id",UUID.class),r.getString("key_hash"),r.getString("request_hash"),
        r.getString("name"),r.getString("email"),r.getString("address"),r.getDate("birth_date").toLocalDate(),
        r.getDate("registered_date").toLocalDate(),r.getInt("dni"),r.getString("billing_status"),
        r.getObject("account_id",UUID.class),r.getString("event_status"),r.getInt("attempts"),
        r.getTimestamp("next_attempt_at").toInstant(),r.getObject("lease_token",UUID.class),
        r.getTimestamp("lease_until").toInstant(),r.getBoolean("deleted"),r.getString("correlation_id"));
    public Optional<RegistrationWork> find(UUID id) {
        return sql.query("SELECT * FROM registration_work WHERE patient_id=?",MAPPER,id).stream().findFirst();
    }
    private Optional<RegistrationWork> byKey(String hash) {
        return sql.query("SELECT * FROM registration_work WHERE key_hash=?",MAPPER,hash).stream().findFirst();
    }
    public RegistrationWork register(PatientRequestDTO dto,String key,String correlation) {
        if(key!=null && !key.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new RegistrationException(400,"Invalid Idempotency-Key");
        }
        String keyHash=key==null?null:hash(key.getBytes(StandardCharsets.UTF_8));
        String requestHash=fingerprint(dto);
        try {
            return tx.execute(status->{
                if(keyHash!=null) {
                    var previous=byKey(keyHash);
                    if(previous.isPresent()) { return replay(previous.get(),requestHash); }
                }
                if(patients.existsByEmail(dto.getEmail()) || patients.existsByDNI(Integer.parseInt(dto.getDNI()))) {
                    throw new PatientAlreadyExistsException("Patient with email or DNI already exists.");
                }
                var patient=patients.saveAndFlush(PatientMapper.toEntity(dto));
                var now=Timestamp.from(Instant.now());
                sql.update("INSERT INTO registration_work (patient_id,key_hash,request_hash,name,email,address,birth_date,registered_date,dni,billing_status,event_status,attempts,next_attempt_at,lease_until,deleted,correlation_id,created_at,recovery_count) VALUES (?,?,?,?,?,?,?,?,?,'PENDING','PENDING',0,?,?,false,?,?,0)",
                    patient.getId(),keyHash,requestHash,patient.getName(),patient.getEmail(),patient.getAddress(),
                    patient.getBirthDate(),patient.getRegisterDate(),patient.getDNI(),Timestamp.from(Instant.EPOCH),Timestamp.from(Instant.EPOCH),correlation,now);
                return find(patient.getId()).orElseThrow();
            });
        } catch(PatientAlreadyExistsException ex) {
            if(keyHash!=null) {
                var previous=byKey(keyHash);
                if(previous.isPresent()) { return replay(previous.get(),requestHash); }
            }
            throw ex;
        } catch(DataIntegrityViolationException ex) {
            if(keyHash!=null) {
                var previous=byKey(keyHash);
                if(previous.isPresent()) { return replay(previous.get(),requestHash); }
            }
            for(Throwable cause=ex;cause!=null;cause=cause.getCause()) {
                if(cause instanceof SQLException s && "23505".equals(s.getSQLState())) {
                    throw new PatientAlreadyExistsException("Patient with email or DNI already exists.");
                }
            }
            throw ex;
        }
    }
    private RegistrationWork replay(RegistrationWork work,String hash) {
        if(work.deleted() || !work.requestHash().equals(hash)) {
            throw new RegistrationException(409,"Idempotency-Key already consumed or incompatible");
        }
        return work;
    }
    public List<UUID> due(Instant now,int limit) {
        return sql.query("SELECT patient_id FROM registration_work WHERE lease_until<=? AND next_attempt_at<=? AND (billing_status IN ('PENDING','UNCERTAIN') OR (billing_status='CONFIRMED' AND event_status<>'CONFIRMED')) ORDER BY next_attempt_at LIMIT ?",
            (r,n)->r.getObject(1,UUID.class),Timestamp.from(now),Timestamp.from(now),limit);
    }
    public Optional<RegistrationWork> claim(UUID id,Instant now,Duration lease) {
        return tx.execute(status->{
            UUID token=UUID.randomUUID();
            int changed=sql.update("UPDATE registration_work SET lease_token=?,lease_until=? WHERE patient_id=? AND lease_until<=? AND next_attempt_at<=? AND (billing_status IN ('PENDING','UNCERTAIN') OR (billing_status='CONFIRMED' AND event_status<>'CONFIRMED'))",
                token,Timestamp.from(now.plus(lease)),id,Timestamp.from(now),Timestamp.from(now));
            return changed==0?Optional.empty():find(id);
        });
    }
    public boolean billingConfirmed(RegistrationWork work,UUID account) {
        return sql.update("UPDATE registration_work SET billing_status='CONFIRMED',recovery_count=recovery_count+CASE WHEN attempts>0 THEN 1 ELSE 0 END,account_id=?,attempts=0,next_attempt_at=?,lease_token=NULL,lease_until=? WHERE patient_id=? AND lease_token=?",
            account,Timestamp.from(Instant.EPOCH),Timestamp.from(Instant.EPOCH),work.id(),work.leaseToken())==1;
    }
    public void eventConfirmed(RegistrationWork work) {
        sql.update("UPDATE registration_work SET event_status='CONFIRMED',recovery_count=recovery_count+CASE WHEN attempts>0 THEN 1 ELSE 0 END,last_error=NULL,lease_token=NULL,lease_until=? WHERE patient_id=? AND lease_token=?",
            Timestamp.from(Instant.EPOCH),work.id(),work.leaseToken());
    }
    public void failed(RegistrationWork work,String billingStatus,String eventStatus,String error,Instant next) {
        sql.update("UPDATE registration_work SET billing_status=?,event_status=?,attempts=attempts+1,last_error=?,next_attempt_at=?,lease_token=NULL,lease_until=? WHERE patient_id=? AND lease_token=?",
            billingStatus,eventStatus,error,Timestamp.from(next),Timestamp.from(Instant.EPOCH),work.id(),work.leaseToken());
    }
    public <T> T mutate(UUID id,boolean delete,Supplier<T> action) {
        return tx.execute(status->{
            var work=sql.query("SELECT * FROM registration_work WHERE patient_id=? FOR UPDATE",MAPPER,id).stream().findFirst();
            if(work.isPresent() && !work.get().billingConfirmed()) {
                throw new RegistrationException(409,"Registration billing is pending; patient cannot be changed");
            }
            T result=action.get();
            if(delete && work.isPresent()) { sql.update("UPDATE registration_work SET deleted=true WHERE patient_id=?",id); }
            return result;
        });
    }
    public Map<String,Long> summary() {
        var result=new LinkedHashMap<String,Long>();
        result.put("billingPending",count("billing_status IN ('PENDING','UNCERTAIN')"));
        result.put("requiresReview",count("billing_status='REVIEW'"));
        result.put("eventsPending",count("billing_status='CONFIRMED' AND event_status<>'CONFIRMED'"));
        result.put("eventsConfirmed",count("event_status='CONFIRMED'"));
        result.put("recoveriesConfirmed",sql.queryForObject("SELECT coalesce(sum(recovery_count),0) FROM registration_work",Long.class));
        return result;
    }
    private long count(String clause) { return sql.queryForObject("SELECT count(*) FROM registration_work WHERE "+clause,Long.class); }
    static String fingerprint(PatientRequestDTO dto) {
        try {
            var bytes=new ByteArrayOutputStream();
            try(var data=new DataOutputStream(bytes)) {
                for(String field:List.of(dto.getName(),dto.getEmail(),dto.getAddress(),
                    LocalDate.parse(dto.getDateOfBirth()).toString(),LocalDate.parse(dto.getRegisteredDate()).toString(),
                    Integer.toString(Integer.parseInt(dto.getDNI())))) {
                    byte[] encoded=field.getBytes(StandardCharsets.UTF_8); data.writeInt(encoded.length); data.write(encoded);
                }
            }
            return hash(bytes.toByteArray());
        } catch(IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
