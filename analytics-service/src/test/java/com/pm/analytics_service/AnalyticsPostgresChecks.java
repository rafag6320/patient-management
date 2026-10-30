package com.pm.analytics_service;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
class AnalyticsPostgresChecks extends AnalyticsProcessingIT {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry r) {
        String port=System.getProperty("remaining.postgres.port");
        if(port==null || !port.matches("[0-9]+")){throw new IllegalStateException("Explicit isolated PostgreSQL required");}
        r.add("spring.datasource.url",()->"jdbc:postgresql://127.0.0.1:"+port+"/remaining_tests?currentSchema=analytics_app");
        r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"postgres");
        r.add("spring.flyway.enabled",()->"true");r.add("spring.flyway.schemas",()->"analytics_app");r.add("spring.sql.init.mode",()->"never");
    }
}
