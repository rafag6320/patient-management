package com.pm.patient_service;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Sólo se selecciona explícitamente desde verify-patient-postgres.ps1. */
class PatientPostgresChecks extends PatientCrudChecks {
    @DynamicPropertySource
    static void isolatedPostgres(DynamicPropertyRegistry registry) {
        String port=System.getProperty("r06.postgres.port");
        if (port==null || !port.matches("[0-9]+")) { throw new IllegalStateException("Explicit isolated PostgreSQL port required"); }
        registry.add("spring.datasource.url",()->"jdbc:postgresql://127.0.0.1:"+port+"/patient_r06");
        registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        registry.add("spring.datasource.username",()->"postgres");
        registry.add("spring.datasource.password",()->"postgres");
    }
}
