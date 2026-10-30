package com.pm.patient_service.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;
import com.pm.patient_service.exception.PatientAlreadyExistsException;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.pm.patient_service.dto.PatientRequestDTO;
import com.pm.patient_service.dto.PatientResponseDTO;
import com.pm.patient_service.entity.Patient;
import com.pm.patient_service.exception.EmailAlreadyExistsException;
import com.pm.patient_service.exception.PatientNotFoundException;
import com.pm.patient_service.mapper.PatientMapper;
import com.pm.patient_service.repository.PatientRepository;

@Service
public class PatientService {
    private final PatientRepository patientRepository;
    private final com.pm.patient_service.registration.RegistrationCoordinator registrations;
    private final com.pm.patient_service.registration.RegistrationStore registrationStore;
    
    public PatientService(PatientRepository patientRepository,
            com.pm.patient_service.registration.RegistrationCoordinator registrations,
            com.pm.patient_service.registration.RegistrationStore registrationStore) {
        this.patientRepository=patientRepository;
        this.registrations=registrations;
        this.registrationStore=registrationStore;
    }

    public List<PatientResponseDTO> getAllPatients() {
        List<Patient> patients = patientRepository.findAll();
        return patients.stream()
                .map(PatientMapper::toDTO)
                .collect(Collectors.toList());
    }

    public PatientResponseDTO createPatient(PatientRequestDTO dto) { return createPatient(dto,null); }

    public PatientResponseDTO createPatient(PatientRequestDTO dto,String key) { return registrations.create(dto,key); }

    public PatientResponseDTO updatePatient(UUID id, PatientRequestDTO patientRequestDTO) {
        return registrationStore.mutate(id,false,()->updateStoredPatient(id,patientRequestDTO));
    }

    private PatientResponseDTO updateStoredPatient(UUID id, PatientRequestDTO patientRequestDTO) {
        Patient existingPatient = patientRepository.findById(id)
                .orElseThrow(() -> new PatientNotFoundException("Patient not found with id: " + id));
        
        if(!existingPatient.getEmail().equals(patientRequestDTO.getEmail()) && 
           patientRepository.existsByEmail(patientRequestDTO.getEmail())) {
            throw new EmailAlreadyExistsException("Patient with email " + patientRequestDTO.getEmail() + " already exists.");
        }

        int dni = Integer.parseInt(patientRequestDTO.getDNI());
        if (!existingPatient.getDNI().equals(dni)) { checkDniAvailable(dni); }
        existingPatient.setName(patientRequestDTO.getName());
        existingPatient.setEmail(patientRequestDTO.getEmail());
        existingPatient.setDNI(Integer.parseInt(patientRequestDTO.getDNI()));
        existingPatient.setAddress(patientRequestDTO.getAddress());
        existingPatient.setBirthDate(LocalDate.parse(patientRequestDTO.getDateOfBirth()));

        Patient updatedPatient = savePatient(existingPatient);
        return PatientMapper.toDTO(updatedPatient);
    }

    public void deletePatient(UUID id) {
        registrationStore.mutate(id,true,()->{ deleteStoredPatient(id); return null; });
    }

    private void deleteStoredPatient(UUID id) {
        if (!patientRepository.existsById(id)) {
            throw new PatientNotFoundException("Patient not found with id: " + id);
        }
        patientRepository.deleteById(id);
    }

    private void checkDniAvailable(int dni) {
        if (patientRepository.existsByDNI(dni)) {
            throw new PatientAlreadyExistsException("Patient with DNI already exists.");
        }
    }

    private Patient savePatient(Patient patient) {
        try {
            // La escritura y su restricción única deben resolverse antes de los efectos remotos.
            return patientRepository.saveAndFlush(patient);
        } catch (DataIntegrityViolationException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    throw new PatientAlreadyExistsException("Patient with email or DNI already exists.");
                }
            }
            throw ex;
        }
    }
}
