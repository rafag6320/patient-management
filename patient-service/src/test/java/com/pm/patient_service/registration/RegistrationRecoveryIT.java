package com.pm.patient_service.registration;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import com.pm.patient_service.grpc.*;
import com.pm.patient_service.kafka.KafkaProducer;
import com.pm.patient_service.repository.PatientRepository;
import com.pm.patient_service.dto.PatientRequestDTO;
import billing.BillingResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:registration-tests;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class RegistrationRecoveryIT {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean RegistrationStore store;
    @Autowired RegistrationCoordinator coordinator;
    @Autowired JdbcTemplate sql;
    @Autowired PatientRepository patients;
    @Autowired MockMvc http;
    @MockitoBean BillingServiceGrpcClient billing;
    @MockitoBean KafkaProducer kafka;
    private final Map<String,UUID> accounts=new ConcurrentHashMap<>();
    private static final String KEY="registration-test-key-0001";
    @BeforeEach void resetData() {
        sql.update("DELETE FROM registration_work"); patients.deleteAll(); reset(billing,kafka); accounts.clear();
        when(billing.createBillingAccount(anyString(),anyString(),anyString())).thenAnswer(i->
            BillingResponse.newBuilder().setSuccess(true).setAccountId(accounts.computeIfAbsent(i.getArgument(0),k->UUID.randomUUID()).toString()).build());
    }
    private String body() { return "{\"name\":\"Synthetic\",\"email\":\"synthetic@example.com\",\"address\":\"Synthetic address\",\"dateOfBirth\":\"1990-01-01\",\"registeredDate\":\"2026-10-30\",\"DNI\":\"123\"}"; }
    private PatientRequestDTO dto() {
        var d=new PatientRequestDTO();d.setName("Synthetic");d.setEmail("synthetic@example.com");d.setAddress("Synthetic address");
        d.setDateOfBirth("1990-01-01");d.setRegisteredDate("2026-10-30");d.setDNI("123");return d;
    }
    private UUID id() { return patients.findAll().getFirst().getId(); }
    private void due(UUID id) { sql.update("UPDATE registration_work SET next_attempt_at=?,lease_until=? WHERE patient_id=?",java.sql.Timestamp.from(Instant.EPOCH),java.sql.Timestamp.from(Instant.EPOCH),id); }
    @Test void replayReturnsOriginalResponseEvenAfterUpdate() throws Exception {
        http.perform(post("/patients").header("Idempotency-Key",KEY).contentType("application/json").content(body())).andExpect(status().isOk());
        UUID id=id();
        http.perform(put("/patients/"+id).contentType("application/json").content(body().replace("Synthetic\"","Changed\""))).andExpect(status().isOk());
        http.perform(post("/patients").header("Idempotency-Key",KEY).contentType("application/json").content(body().replace("\"123\"","\"00123\"")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.name").value("Synthetic"));
        assertEquals(1,patients.count());verify(billing,times(1)).createBillingAccount(anyString(),anyString(),anyString());verifyNoInteractions(kafka);
        assertEquals(1L,store.summary().get("eventsPending"));
    }
    @Test void keyConflictAndDeletedTombstoneNeverCreateAnotherPatient() throws Exception {
        coordinator.create(dto(),KEY);
        http.perform(post("/patients").header("Idempotency-Key",KEY).contentType("application/json").content(body().replace("123","456"))).andExpect(status().isConflict());
        http.perform(delete("/patients/"+id())).andExpect(status().isNoContent());
        http.perform(post("/patients").header("Idempotency-Key",KEY).contentType("application/json").content(body())).andExpect(status().isConflict());
        assertEquals(0,patients.count());assertEquals(1,accounts.size());
    }
    @Test void unavailableBillingPersistsPendingAndBlocksMutationsUntilRecovered() throws Exception {
        when(billing.createBillingAccount(anyString(),anyString(),anyString())).thenThrow(new BillingCallException(BillingCallException.Kind.RETRYABLE));
        http.perform(post("/patients").header("Idempotency-Key",KEY).contentType("application/json").content(body())).andExpect(status().isServiceUnavailable());
        UUID id=id();assertFalse(store.find(id).orElseThrow().billingConfirmed());
        http.perform(put("/patients/"+id).contentType("application/json").content(body())).andExpect(status().isConflict());
        http.perform(delete("/patients/"+id)).andExpect(status().isConflict());verifyNoInteractions(kafka);
        doReturn(BillingResponse.newBuilder().setSuccess(true).setAccountId(UUID.randomUUID().toString()).build()).when(billing).createBillingAccount(anyString(),anyString(),anyString());
        due(id);coordinator.process(id,true);assertTrue(store.find(id).orElseThrow().billingConfirmed());
        coordinator.process(id,true);verify(kafka).sendEvent(argThat(p->p.getId().equals(id)));assertEquals("CONFIRMED",store.find(id).orElseThrow().eventStatus());
    }
    @Test void recoversCommittedPatientBeforeRpcAndLostRemoteResponse() {
        var work=store.register(dto(),KEY,"test-correlation");assertEquals(1,patients.count());verifyNoInteractions(billing,kafka);
        UUID account=UUID.randomUUID();
        doAnswer(i->{accounts.put(i.getArgument(0),account);throw new BillingCallException(BillingCallException.Kind.UNCERTAIN);}).when(billing).createBillingAccount(anyString(),anyString(),anyString());
        coordinator.process(work.id(),true);assertEquals("UNCERTAIN",store.find(work.id()).orElseThrow().billingStatus());
        doReturn(BillingResponse.newBuilder().setSuccess(true).setAccountId(account.toString()).build()).when(billing).createBillingAccount(anyString(),anyString(),anyString());
        due(work.id());coordinator.process(work.id(),true);coordinator.process(work.id(),true);
        assertEquals(account,store.find(work.id()).orElseThrow().accountId());assertEquals(1,accounts.size());assertEquals("CONFIRMED",store.find(work.id()).orElseThrow().eventStatus());
    }
    @Test void localConfirmationFailureRepeatsRpcWithoutAnotherAccount() {
        var work=store.register(dto(),KEY,null);
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("synthetic commit failure"))
            .doCallRealMethod().when(store).billingConfirmed(any(),any());
        coordinator.process(work.id(),true);assertEquals("UNCERTAIN",store.find(work.id()).orElseThrow().billingStatus());
        UUID account=accounts.get(work.id().toString());due(work.id());coordinator.process(work.id(),true);
        assertEquals(account,store.find(work.id()).orElseThrow().accountId());assertEquals(1,accounts.size());
    }
    @Test void uncertainKafkaAckIsRetriedWithSameImmutableIdentity() {
        coordinator.create(dto(),KEY);UUID id=id();
        doThrow(new IllegalStateException("synthetic lost ack")).doNothing().when(kafka).sendEvent(any());
        coordinator.process(id,true);assertEquals("UNCERTAIN",store.find(id).orElseThrow().eventStatus());
        due(id);coordinator.process(id,true);verify(kafka,times(2)).sendEvent(argThat(p->p.getId().equals(id)&&p.getName().equals("Synthetic")));
        assertEquals("CONFIRMED",store.find(id).orElseThrow().eventStatus());
    }
    @Test void falseResponseAndUncertainBudgetNeverPublishOrReturnSuccess() {
        when(billing.createBillingAccount(anyString(),anyString(),anyString())).thenReturn(BillingResponse.getDefaultInstance());
        assertEquals(502,assertThrows(RegistrationException.class,()->coordinator.create(dto(),KEY)).status());
        assertEquals("REVIEW",store.find(id()).orElseThrow().billingStatus());verifyNoInteractions(kafka);
    }
    @Test void uncertainFailuresEventuallyRequireReviewWithoutDiscardingWork() {
        var work=store.register(dto(),KEY,null);
        when(billing.createBillingAccount(anyString(),anyString(),anyString())).thenThrow(new BillingCallException(BillingCallException.Kind.UNCERTAIN));
        for(int i=0;i<5;i++) { due(work.id());coordinator.process(work.id(),true); }
        assertEquals("REVIEW",store.find(work.id()).orElseThrow().billingStatus());assertEquals(1,patients.count());verifyNoInteractions(kafka);
    }
    @Test void leasePreventsParallelEffectsAndFencesObsoleteOwner() {
        var work=store.register(dto(),KEY,null);Instant now=Instant.now();
        var first=store.claim(work.id(),now,Duration.ofSeconds(45)).orElseThrow();
        assertTrue(store.claim(work.id(),now,Duration.ofSeconds(45)).isEmpty());
        var successor=store.claim(work.id(),now.plusSeconds(46),Duration.ofSeconds(45)).orElseThrow();
        assertFalse(store.billingConfirmed(first,UUID.randomUUID()));
        UUID winner=UUID.randomUUID();assertTrue(store.billingConfirmed(successor,winner));assertEquals(winner,store.find(work.id()).orElseThrow().accountId());
    }
    @Test void sqlFailureRollsBackPatientAndNeverCallsRemoteServices() throws Exception {
        sql.execute("ALTER TABLE registration_work ADD CONSTRAINT synthetic_failure CHECK (dni<>123)");
        try {
            http.perform(post("/patients").header("Idempotency-Key",KEY).contentType("application/json").content(body())).andExpect(status().isServiceUnavailable());
            assertEquals(0,patients.count());verifyNoInteractions(billing,kafka);
        } finally { sql.execute("ALTER TABLE registration_work DROP CONSTRAINT synthetic_failure"); }
    }
    @Test void concurrentRequestsWithSameKeyReserveOnlyOneOperation() throws Exception {
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<RegistrationWork>>();
            for(int i=0;i<4;i++) { futures.add(executor.submit(()->{start.await();return store.register(dto(),KEY,null);})); }
            start.countDown();var ids=new HashSet<UUID>();
            for(var f:futures) {ids.add(f.get(10,TimeUnit.SECONDS).id());}
            assertEquals(1,ids.size());assertEquals(1,patients.count());assertEquals(1L,sql.queryForObject("SELECT count(*) FROM registration_work",Long.class));
        }
    }
    @Test void invalidKeyDoesNotReserveOperation() throws Exception {
        http.perform(post("/patients").header("Idempotency-Key","bad").contentType("application/json").content(body())).andExpect(status().isBadRequest());
        assertEquals(0,patients.count());verifyNoInteractions(billing,kafka);
    }
}
