package com.pm.patient_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.pm.patient_service.dto.PatientRequestDTO;
import com.pm.patient_service.entity.Patient;
import com.pm.patient_service.mapper.PatientMapper;

@SpringBootTest
class PatientServiceApplicationTests {

	@Test
	void contextLoads() {
	}

	@Test
	void mapsRegisteredDateToPatient() {
		PatientRequestDTO request = new PatientRequestDTO();
		request.setName("Test Patient");
		request.setEmail("test.patient@example.com");
		request.setAddress("Test Address 123");
		request.setDateOfBirth("1990-01-15");
		request.setRegisteredDate("2026-08-24");
		request.setDNI("45678901");

		Patient patient = PatientMapper.toEntity(request);

		org.junit.jupiter.api.Assertions.assertEquals("2026-08-24", patient.getRegisterDate().toString());
	}

}
