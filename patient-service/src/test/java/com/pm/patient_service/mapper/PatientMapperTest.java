package com.pm.patient_service.mapper;

import com.pm.patient_service.dto.PatientRequestDTO;
import com.pm.patient_service.entity.Patient;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PatientMapperTest {
    @Test
    void mapsEveryRequestFieldWithoutSpring() {
        PatientRequestDTO request = new PatientRequestDTO();
        request.setName("Test Patient");
        request.setEmail("test.patient@example.com");
        request.setAddress("Test Address 123");
        request.setDateOfBirth("1990-01-15");
        request.setRegisteredDate("2026-08-24");
        request.setDNI("45678901");

        Patient patient = PatientMapper.toEntity(request);

        assertAll(
            () -> assertEquals(request.getName(), patient.getName()),
            () -> assertEquals(request.getEmail(), patient.getEmail()),
            () -> assertEquals(request.getAddress(), patient.getAddress()),
            () -> assertEquals(LocalDate.of(1990, 1, 15), patient.getBirthDate()),
            () -> assertEquals(LocalDate.of(2026, 8, 24), patient.getRegisterDate()),
            () -> assertEquals(45678901, patient.getDNI()),
            () -> assertNull(patient.getId()));
    }

    @Test
    void mapsPersistedPatientToPublicResponse() {
        Patient patient = new Patient(UUID.randomUUID(), "Test Patient", 45678901,
            "test.patient@example.com", "Test Address 123", LocalDate.of(1990, 1, 15),
            LocalDate.of(2026, 8, 24));
        var response = PatientMapper.toDTO(patient);
        assertAll(
            () -> assertEquals(patient.getId().toString(), response.getId()),
            () -> assertEquals(patient.getName(), response.getName()),
            () -> assertEquals(patient.getEmail(), response.getEmail()),
            () -> assertEquals(patient.getAddress(), response.getAddress()),
            () -> assertEquals("1990-01-15", response.getDateOfBirth()),
            () -> assertEquals("45678901", response.getDni()));
    }
}
