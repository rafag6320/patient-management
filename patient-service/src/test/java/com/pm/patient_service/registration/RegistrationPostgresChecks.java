package com.pm.patient_service.registration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
class RegistrationPostgresChecks extends RegistrationRecoveryIT {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry r) {
        String port=System.getProperty("remaining.postgres.port");
        if(port==null || !port.matches("[0-9]+")){throw new IllegalStateException("Explicit isolated PostgreSQL required");}
        r.add("spring.datasource.url",()->"jdbc:postgresql://127.0.0.1:"+port+"/remaining_tests?currentSchema=registration_app");
        r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
        r.add("spring.flyway.enabled",()->"true");r.add("spring.flyway.schemas",()->"registration_app");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
    }
}
