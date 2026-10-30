package com.pm.patient_service;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class PatientMigratedMigrationChecks extends PatientCrudChecks {
    @DynamicPropertySource
    static void migratedDatabase(DynamicPropertyRegistry registry) {
        String port=System.getProperty("r07.postgres.port");
        if (port==null || !port.matches("[0-9]+")) { throw new IllegalStateException("Isolated PostgreSQL port required"); }
        registry.add("spring.datasource.url",()->"jdbc:postgresql://127.0.0.1:"+port+"/migration_r07?currentSchema=patient_app");
        registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        registry.add("spring.datasource.username",()->"postgres");
        registry.add("spring.datasource.password",()->"postgres");
        registry.add("spring.flyway.enabled",()->"true");
        registry.add("spring.flyway.schemas",()->"patient_app");
        registry.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        registry.add("spring.jpa.defer-datasource-initialization",()->"false");
    }
}
