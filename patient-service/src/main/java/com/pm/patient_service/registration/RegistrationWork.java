package com.pm.patient_service.registration;

import java.time.*;
import java.util.UUID;
import com.pm.patient_service.entity.Patient;
import com.pm.patient_service.dto.PatientResponseDTO;
import com.pm.patient_service.mapper.PatientMapper;

public record RegistrationWork(UUID id, String keyHash, String requestHash, String name, String email,
        String address, LocalDate birthDate, LocalDate registeredDate, int dni, String billingStatus,
        UUID accountId, String eventStatus, int attempts, Instant nextAttempt, UUID leaseToken,
        Instant leaseUntil, boolean deleted, String correlationId) {
    public Patient patient() { return new Patient(id,name,dni,email,address,birthDate,registeredDate); }
    public PatientResponseDTO response() { return PatientMapper.toDTO(patient()); }
    public boolean billingConfirmed() { return "CONFIRMED".equals(billingStatus); }
}
