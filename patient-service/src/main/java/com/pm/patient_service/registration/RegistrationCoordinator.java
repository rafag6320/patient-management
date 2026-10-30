package com.pm.patient_service.registration;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.pm.patient_service.dto.*;
import com.pm.patient_service.grpc.*;
import com.pm.patient_service.kafka.KafkaProducer;

@Service
public class RegistrationCoordinator {
    private final RegistrationStore store;
    private final BillingServiceGrpcClient billing;
    private final KafkaProducer kafka;
    private final int uncertainBudget;
    public RegistrationCoordinator(RegistrationStore store,BillingServiceGrpcClient billing,KafkaProducer kafka,
            @Value("${registration.uncertain-budget:5}") int uncertainBudget) {
        if(uncertainBudget<1 || uncertainBudget>100) { throw new IllegalArgumentException("registration.uncertain-budget must be 1..100"); }
        this.store=store;this.billing=billing;this.kafka=kafka;this.uncertainBudget=uncertainBudget;
    }
    public PatientResponseDTO create(PatientRequestDTO dto,String key) {
        var work=store.register(dto,key,org.slf4j.MDC.get("correlationId"));
        if(!work.billingConfirmed()) { process(work.id(),false); }
        work=store.find(work.id()).orElseThrow();
        if(work.deleted()) { throw new RegistrationException(409,"Idempotency-Key already consumed"); }
        if(!work.billingConfirmed()) {
            throw new RegistrationException("REVIEW".equals(work.billingStatus())?502:503,
                "Registration pending or requires review; repeat with the same Idempotency-Key");
        }
        return work.response();
    }
    public void process(UUID id,boolean publish) {
        var claimed=store.claim(id,Instant.now(),Duration.ofSeconds(45));
        if(claimed.isEmpty()) { return; }
        var work=claimed.get();
        String previous=org.slf4j.MDC.get("correlationId");
        try {
            if(work.correlationId()!=null) { org.slf4j.MDC.put("correlationId",work.correlationId()); }
            if(!work.billingConfirmed()) {
                var result=billing.createBillingAccount(work.id().toString(),work.name(),work.email());
                if(result==null || !result.getSuccess()) { throw new BillingCallException(BillingCallException.Kind.REVIEW); }
                UUID account;
                try { account=UUID.fromString(result.getAccountId()); }
                catch(IllegalArgumentException ex) { throw new BillingCallException(BillingCallException.Kind.REVIEW); }
                store.billingConfirmed(work,account);
            } else if(publish) {
                kafka.sendEvent(work.patient());
                store.eventConfirmed(work);
            } else {
                store.failed(work,work.billingStatus(),work.eventStatus(),"DEFERRED",Instant.now());
            }
        } catch(BillingCallException ex) {
            boolean review=ex.kind()==BillingCallException.Kind.REVIEW ||
                (ex.kind()==BillingCallException.Kind.UNCERTAIN && work.attempts()+1>=uncertainBudget);
            store.failed(work,review?"REVIEW":"UNCERTAIN",work.eventStatus(),ex.kind().name(),next(work.attempts()));
        } catch(RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Registration result unconfirmed: {}",ex.getClass().getSimpleName());
            // SQL may have committed remotely or locally. A failed state write leaves the lease to expire.
            if(work.billingConfirmed()) { store.failed(work,"CONFIRMED","UNCERTAIN","EVENT_UNCERTAIN",next(work.attempts())); }
            else { store.failed(work,work.attempts()+1>=uncertainBudget?"REVIEW":"UNCERTAIN",work.eventStatus(),"RESULT_UNCERTAIN",next(work.attempts())); }
        } finally {
            if(previous==null) { org.slf4j.MDC.remove("correlationId"); } else { org.slf4j.MDC.put("correlationId",previous); }
        }
    }
    private Instant next(int attempts) {
        long seconds=Math.min(60,1L<<Math.min(attempts,6));
        return Instant.now().plusMillis(seconds*1000+ThreadLocalRandom.current().nextLong(201));
    }
}
