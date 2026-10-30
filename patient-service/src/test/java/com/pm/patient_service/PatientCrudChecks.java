package com.pm.patient_service;

import java.util.UUID;
import billing.BillingResponse;
import java.util.concurrent.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.pm.patient_service.grpc.BillingServiceGrpcClient;
import com.pm.patient_service.kafka.KafkaProducer;
import com.pm.patient_service.repository.PatientRepository;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:application-test.properties")
abstract class PatientCrudChecks {
    @Autowired MockMvc http;
    @Autowired PatientRepository patients;
    @MockitoBean BillingServiceGrpcClient billing;
    @MockitoBean KafkaProducer kafka;

    @BeforeEach
    void cleanIsolatedDatabase() {
        patients.deleteAll(); reset(billing,kafka);
        when(billing.createBillingAccount(anyString(),anyString(),anyString())).thenAnswer(inv ->
            BillingResponse.newBuilder().setSuccess(true).setAccountId(UUID.randomUUID().toString()).build());
    }

    String body(String email, String dni) {
        return """
            {"name":"Test Patient","email":"%s","address":"Test Address",
             "dateOfBirth":"1990-01-15","registeredDate":"2026-10-28","DNI":"%s"}
            """.formatted(email, dni);
    }

    String create(String email, String dni) throws Exception {
        http.perform(post("/patients").contentType("application/json").content(body(email,dni)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").isNotEmpty());
        return patients.findAll().stream().filter(p -> p.getEmail().equals(email)).findFirst().orElseThrow().getId().toString();
    }

    @Test
    void crudKeepsExistingContractsAndAllowsUnchangedIdentifiers() throws Exception {
        String id=create("one@example.com","123");
        http.perform(get("/patients")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        http.perform(put("/patients/"+id).contentType("application/json")
                .content(body("one@example.com","123").replace("Test Patient","Updated")
                        .replace(",\"registeredDate\":\"2026-10-28\"", "")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Updated"));
        verify(billing,times(1)).createBillingAccount(anyString(),anyString(),anyString());
        verifyNoInteractions(kafka);
        http.perform(delete("/patients/"+id)).andExpect(status().isNoContent());
        assertEquals(0,patients.count());
    }

    @Test
    void nonexistentAndMalformedIdsAreControlled() throws Exception {
        String path="/patients/"+UUID.randomUUID();
        http.perform(put(path).contentType("application/json").content(body("one@example.com","123")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").exists());
        http.perform(delete(path)).andExpect(status().isNotFound());
        http.perform(delete("/patients/not-a-uuid")).andExpect(status().isBadRequest());
        verifyNoInteractions(billing,kafka);
    }

    @ParameterizedTest
    @ValueSource(strings={"abc","-1","+1","1.5","2147483648","9999999999","12345678901","１２３"})
    void invalidDniNeverWritesOrCallsIntegrations(String dni) throws Exception {
        http.perform(post("/patients").contentType("application/json").content(body("one@example.com",dni)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.DNI").exists());
        http.perform(put("/patients/"+UUID.randomUUID()).contentType("application/json").content(body("one@example.com",dni)))
                .andExpect(status().isBadRequest());
        assertEquals(0,patients.count()); verifyNoInteractions(billing,kafka);
    }

    @ParameterizedTest
    @ValueSource(strings={"1990-02-30","2025-02-29","30/01/1990","1990-1-15","not-a-date"})
    void invalidDatesAreFieldErrors(String date) throws Exception {
        for (String field : List.of("dateOfBirth","registeredDate")) {
            String payload=body("one@example.com","123").replace(field.equals("dateOfBirth")?"1990-01-15":"2026-10-28",date);
            http.perform(post("/patients").contentType("application/json").content(payload))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$."+field).exists());
            http.perform(put("/patients/"+UUID.randomUUID()).contentType("application/json").content(payload))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(0,patients.count()); verifyNoInteractions(billing,kafka);
    }

    @Test
    void missingFieldsAndMalformedJsonAreBadRequests() throws Exception {
        http.perform(post("/patients").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.registeredDate").exists());
        http.perform(post("/patients").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(billing,kafka);
    }

    @Test
    void invalidAndOversizedEmailAreRejectedBeforePersistence() throws Exception {
        for (String email:List.of("not-an-email", "a@"+("a".repeat(60)+".").repeat(5)+"com")) {
            http.perform(post("/patients").contentType("application/json").content(body(email,"123")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.email").exists());
        }
        assertEquals(0,patients.count()); verifyNoInteractions(billing,kafka);
    }

    @ParameterizedTest
    @ValueSource(strings={"0","2147483647"})
    void acceptsStorageBoundariesAndLeapDay(String dni) throws Exception {
        http.perform(post("/patients").contentType("application/json")
                .content(body("boundary@example.com",dni).replace("1990-01-15","2024-02-29")))
                .andExpect(status().isOk());
        assertEquals(1,patients.count());
    }

    @Test
    void duplicatesOnCreateAndUpdateDoNotChangeStoredData() throws Exception {
        create("one@example.com","123");
        String second=create("two@example.com","456");
        for (String payload:List.of(body("one@example.com","789"),body("three@example.com","123"))) {
            http.perform(post("/patients").contentType("application/json").content(payload))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
            http.perform(put("/patients/"+second).contentType("application/json").content(payload))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(2,patients.count());
        assertEquals("two@example.com",patients.findById(UUID.fromString(second)).orElseThrow().getEmail());
        verify(billing,times(2)).createBillingAccount(anyString(),anyString(),anyString());
        verifyNoInteractions(kafka);
    }

    @ParameterizedTest
    @ValueSource(booleans={true,false})
    void concurrentCreatesAcceptOnlyOneDuplicate(boolean sameEmail) throws Exception {
        String first=body("race@example.com","123");
        String second=body(sameEmail?"race@example.com":"other@example.com",sameEmail?"456":"123");
        var statuses=concurrent("POST","/patients",first,"/patients",second);
        assertEquals(List.of(200,400),statuses);
        assertEquals(1,patients.count());
        verify(billing,times(1)).createBillingAccount(anyString(),anyString(),anyString());
        verifyNoInteractions(kafka);
    }

    @ParameterizedTest
    @ValueSource(booleans={true,false})
    void concurrentUpdatesAcceptOnlyOneDuplicate(boolean sameEmail) throws Exception {
        String a=create("one@example.com","123"), b=create("two@example.com","456");
        String first=body("shared@example.com","789");
        String second=body(sameEmail?"shared@example.com":"other@example.com",sameEmail?"999":"789");
        assertEquals(List.of(200,400),concurrent("PUT","/patients/"+a,first,"/patients/"+b,second));
        assertEquals(2,patients.count());
    }

    private List<Integer> concurrent(String method,String pathA,String bodyA,String pathB,String bodyB) throws Exception {
        var start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> { start.await(); return http.perform(request(org.springframework.http.HttpMethod.valueOf(method),pathA)
                    .contentType("application/json").content(bodyA)).andReturn().getResponse().getStatus(); });
            var b=executor.submit(() -> { start.await(); return http.perform(request(org.springframework.http.HttpMethod.valueOf(method),pathB)
                    .contentType("application/json").content(bodyB)).andReturn().getResponse().getStatus(); });
            start.countDown();
            return java.util.stream.Stream.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)).sorted().toList();
        }
    }
}
