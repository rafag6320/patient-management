package com.pm.auth_service;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class AuthMigratedMigrationChecks extends AuthServiceApplicationIT {
    @org.springframework.beans.factory.annotation.Autowired
    private javax.sql.DataSource migratedDataSource;

    @Override
    @org.junit.jupiter.api.Test
    void contextLoads() throws Exception {
        try (var connection=migratedDataSource.getConnection()) {
            org.junit.jupiter.api.Assertions.assertTrue(connection.getMetaData().getURL().startsWith("jdbc:postgresql://127.0.0.1:"));
            try (var statement=connection.createStatement(); var rows=statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success AND version='1' AND type='SQL'")) {
                rows.next(); org.junit.jupiter.api.Assertions.assertEquals(1,rows.getInt(1));
            }
        }
    }
    @DynamicPropertySource
    static void migratedDatabase(DynamicPropertyRegistry registry) {
        String port=System.getProperty("r07.postgres.port");
        if (port==null || !port.matches("[0-9]+")) { throw new IllegalStateException("Isolated PostgreSQL port required"); }
        registry.add("spring.datasource.url",()->"jdbc:postgresql://127.0.0.1:"+port+"/migration_r07?currentSchema=auth_app");
        registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        registry.add("spring.datasource.username",()->"postgres");
        registry.add("spring.datasource.password",()->"postgres");
        registry.add("spring.flyway.enabled",()->"true");
        registry.add("spring.flyway.schemas",()->"auth_app");
        registry.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        registry.add("spring.jpa.defer-datasource-initialization",()->"false");
    }
}
