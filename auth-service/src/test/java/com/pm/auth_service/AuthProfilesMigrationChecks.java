package com.pm.auth_service;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Uses only the ephemeral PostgreSQL created by verify-migrations-postgres.ps1. */
class AuthProfilesMigrationChecks {
    private String url(String schema) {
        String port=System.getProperty("r07.postgres.port");
        if (port==null || !port.matches("[0-9]+")) { throw new IllegalStateException("Isolated PostgreSQL required"); }
        return "jdbc:postgresql://127.0.0.1:"+port+"/migration_r07?currentSchema="+schema;
    }
    private String schema() { return "profile_"+UUID.randomUUID().toString().replace("-",""); }
    private ConfigurableApplicationContext start(String profile,String schema,String...extra) {
        var arguments=new java.util.ArrayList<>(java.util.List.of(
            "--server.port=0", "--spring.datasource.url="+url(schema),
            "--spring.datasource.username=postgres", "--spring.datasource.password=postgres",
            "--spring.flyway.schemas="+schema, "--jwt.secret=ephemeral-profile-test-key-xxxxxxxxxxxxxxxx",
            "--spring.main.banner-mode=off", "--spring.jmx.enabled=false"));
        arguments.addAll(java.util.List.of(extra));
        return new SpringApplicationBuilder(AuthServiceApplication.class).profiles(profile)
            .web(WebApplicationType.SERVLET).run(arguments.toArray(String[]::new));
    }

    @Test
    void productionCreatesSchemaWithoutDevelopmentAccount() {
        try (var context=start("prod",schema())) {
            var jdbc=new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM users",Integer.class));
            assertEquals("never",context.getEnvironment().getProperty("spring.sql.init.mode"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"local","docker"})
    void developmentSeedIsIdempotentAndDoesNotOverwriteExistingHash(String profile) {
        String schema=schema();
        try (var context=start(profile,schema)) {
            var jdbc=new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM users WHERE role='ADMIN'",Integer.class));
            // Synthetic database only: exercise the existing seed's WHERE NOT EXISTS guard.
            jdbc.update("UPDATE users SET password='synthetic-existing-hash',role='USER'");
        }
        try (var context=start(profile,schema)) {
            var jdbc=new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM users",Integer.class));
            assertEquals("synthetic-existing-hash",jdbc.queryForObject("SELECT password FROM users",String.class));
            assertEquals("USER",jdbc.queryForObject("SELECT role FROM users",String.class));
        }
    }

    @Test
    void productionRejectsForcedSeedBeforeConnectingOrMigrating() throws Exception {
        String schema=schema();
        assertThrows(RuntimeException.class,()->start("prod",schema,"--spring.sql.init.mode=always",
            "--spring.sql.init.data-locations=classpath:db/dev/data.sql"));
        try (var connection=DriverManager.getConnection(url("public"),"postgres","postgres");
             var statement=connection.prepareStatement("SELECT count(*) FROM pg_namespace WHERE nspname=?")) {
            statement.setString(1,schema);
            try (var rows=statement.executeQuery()) { rows.next(); assertEquals(0,rows.getInt(1)); }
        }
    }
}
