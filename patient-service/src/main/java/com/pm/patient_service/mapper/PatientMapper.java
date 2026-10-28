package com.pm.patient_service.mapper;

import java.time.LocalDate;
import com.pm.patient_service.dto.PatientRequestDTO;
import com.pm.patient_service.dto.PatientResponseDTO;
import com.pm.patient_service.entity.Patient;

public class PatientMapper {
    public static PatientResponseDTO toDTO(Patient patient) {
        return new PatientResponseDTO(
            patient.getId().toString(),
            patient.getName(),
            patient.getEmail(),
            patient.getAddress(),
            patient.getBirthDate().toString(),
            patient.getDNI().toString()
        );
    }

    public static Patient toEntity(PatientRequestDTO patientRequestDTO) {
        Patient patient = new Patient();
        patient.setName(patientRequestDTO.getName());
        patient.setEmail(patientRequestDTO.getEmail());
        patient.setAddress(patientRequestDTO.getAddress());
        patient.setBirthDate(LocalDate.parse(patientRequestDTO.getDateOfBirth()));
        patient.setRegisterDate(LocalDate.parse(patientRequestDTO.getRegisteredDate()));
        patient.setDNI(Integer.parseInt(patientRequestDTO.getDNI()));
        return patient;
    }
}
