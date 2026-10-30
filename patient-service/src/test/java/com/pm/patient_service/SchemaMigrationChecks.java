package com.pm.patient_service;

import java.nio.file.*;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchemaMigrationChecks {
    private String url() {
        String port=System.getProperty("r07.postgres.port");
        if (port==null || !port.matches("[0-9]+")) { throw new IllegalStateException("Explicit isolated PostgreSQL required"); }
        return "jdbc:postgresql://127.0.0.1:"+port+"/migration_r07";
    }
    private Connection connection(String schema) throws Exception {
        var c=DriverManager.getConnection(url(),"postgres","postgres");
        c.setSchema(schema); return c;
    }
    private String schema() throws Exception {
        String name="test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=connection("public");var s=c.createStatement()) { s.execute("CREATE SCHEMA "+name); }
        return name;
    }
    private Flyway flyway(String schema,String...locations) {
        return Flyway.configure().dataSource(url(),"postgres","postgres").schemas(schema).defaultSchema(schema)
            .baselineOnMigrate(false).baselineVersion("1").cleanDisabled(true).locations(locations).load();
    }
    private void sql(String schema,String sql) throws Exception {
        try(var c=connection(schema);var s=c.createStatement()) { s.execute(sql); }
    }
    private String value(String schema,String sql) throws Exception {
        try(var c=connection(schema);var s=c.createStatement();var r=s.executeQuery(sql)) {
            assertTrue(r.next()); return r.getString(1);
        }
    }
    private void preflight(String schema) throws Exception {
        try(var in=getClass().getResourceAsStream("/db/adoption/verify-schema.sql")) {
            sql(schema,new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    @Test
    void freshAndCopiedHibernateSchemaConvergeWithoutLosingRows() throws Exception {
        String fresh=schema(),legacy=schema(),copy=schema();
        Flyway.configure().configuration(flyway(fresh,"classpath:db/migration").getConfiguration()).target("1").load().migrate();
        preflight(fresh); flyway(fresh,"classpath:db/migration").migrate();
        var configuration=new Configuration().addAnnotatedClass(com.pm.patient_service.entity.Patient.class);
        configuration.setProperty("hibernate.connection.url",url()+"?currentSchema="+legacy);
        configuration.setProperty("hibernate.connection.username","postgres");
        configuration.setProperty("hibernate.connection.password","postgres");
        configuration.setPhysicalNamingStrategy(new org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy());
        configuration.setProperty("hibernate.hbm2ddl.auto","create");
        try(var sessionFactory=configuration.buildSessionFactory()) { assertTrue(sessionFactory.isOpen()); }
        sql(legacy,"INSERT INTO patient (id,name,dni,email,address,birth_date,register_date) VALUES ('123e4567-e89b-12d3-a456-426614174000','Preserved',123,'preserved@example.com','Address','1990-01-01','2026-10-28')");
        sql(copy,"CREATE TABLE patient (LIKE "+legacy+".patient INCLUDING ALL); INSERT INTO patient SELECT * FROM "+legacy+".patient");
        String snapshot=value(copy,"SELECT row_to_json(t)::text FROM patient t");
        preflight(copy);
        var adoption=flyway(copy,"classpath:db/migration");
        assertThrows(org.flywaydb.core.api.FlywayException.class,adoption::migrate);
        adoption.baseline(); adoption.migrate(); adoption.validate();
        assertEquals(snapshot,value(copy,"SELECT row_to_json(t)::text FROM patient t"));
        assertEquals("BASELINE",value(copy,"SELECT type FROM flyway_schema_history WHERE version='1'"));
        assertEquals("3",value(fresh,"SELECT max(version) FROM flyway_schema_history WHERE success"));
        assertEquals("0",value(copy,"SELECT count(*) FROM registration_work"));
        // A missing uniqueness guarantee must block adoption before recording history.
        String drift=schema();
        sql(drift,"CREATE TABLE patient (LIKE "+legacy+".patient INCLUDING DEFAULTS INCLUDING CONSTRAINTS)");
        assertThrows(SQLException.class,()->preflight(drift));
        assertNull(value(drift,"SELECT to_regclass('"+drift+".flyway_schema_history')::text"));
    }
    @Test
    void failedTransactionalMigrationRollsBackAndCanResume() throws Exception {
        String target=schema(); var directory=Files.createTempDirectory("r07-unapplied-");
        var flyway=flyway(target,"classpath:db/migration","filesystem:"+directory.toAbsolutePath());
        flyway.migrate(); sql(target,"INSERT INTO patient (id,name,dni,email,address,birth_date,register_date) VALUES ('123e4567-e89b-12d3-a456-426614174000','Preserved',123,'preserved@example.com','Address','1990-01-01','2026-10-28')");
        String before=value(target,"SELECT row_to_json(t)::text FROM patient t");
        var pending=directory.resolve("V99__test_failure.sql");
        try {
            Files.writeString(pending,"CREATE TABLE rollback_probe (id integer); INSERT INTO missing_table VALUES(1);");
            assertThrows(org.flywaydb.core.api.FlywayException.class,flyway::migrate);
            assertNull(value(target,"SELECT to_regclass('"+target+".rollback_probe')::text"));
            assertEquals(before,value(target,"SELECT row_to_json(t)::text FROM patient t"));
            assertEquals("3",value(target,"SELECT count(*) FROM flyway_schema_history WHERE success"));
            // Only this never-applied temporary test script is corrected, never repository migrations.
            Files.writeString(pending,"CREATE TABLE rollback_probe (id integer);");
            flyway.migrate(); flyway.validate();
            assertEquals("4",value(target,"SELECT count(*) FROM flyway_schema_history WHERE success"));
            assertEquals(before,value(target,"SELECT row_to_json(t)::text FROM patient t"));
        } finally { Files.deleteIfExists(pending); Files.deleteIfExists(directory); }
    }
}
