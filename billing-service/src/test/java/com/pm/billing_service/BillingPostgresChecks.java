package com.pm.billing_service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import billing.BillingRequest;
import billing.BillingServiceGrpc;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.server.event.GrpcServerStartedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.context.annotation.Import(BillingServiceApplicationIT.GrpcTestConfiguration.class)
class BillingPostgresChecks extends BillingServiceApplicationIT {
    @org.springframework.beans.factory.annotation.Autowired
    private javax.sql.DataSource migratedDatasource;

    @Test void usesRealPostgresWithVersionedSchema() throws Exception {
        try (var connection=migratedDatasource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:postgresql://127.0.0.1:"));
            try (var statement=connection.createStatement();var rows=statement.executeQuery(
                    "SELECT count(*) FROM flyway_schema_history WHERE version='1' AND type='SQL' AND success")) {
                rows.next(); assertEquals(1,rows.getInt(1));
            }
        }
    }
    private static String url(String schema) {
        String port=System.getProperty("r09.postgres.port");
        if (port==null || !port.matches("[0-9]+")) { throw new IllegalStateException("Explicit isolated PostgreSQL required"); }
        return "jdbc:postgresql://127.0.0.1:"+port+"/billing_r09?currentSchema="+schema;
    }
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->url("billing_rpc"));
        registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        registry.add("spring.datasource.username",()->"postgres"); registry.add("spring.datasource.password",()->"postgres");
        registry.add("spring.flyway.enabled",()->"true"); registry.add("spring.flyway.schemas",()->"billing_rpc");
        registry.add("spring.jpa.hibernate.ddl-auto",()->"validate");
    }
    private ConfigurableApplicationContext start(String schema,AtomicInteger port) {
        return new SpringApplicationBuilder(BillingServiceApplication.class).profiles("test").web(WebApplicationType.NONE)
            .listeners((ApplicationListener<GrpcServerStartedEvent>) event->port.set(event.getPort()))
            .run("--spring.datasource.url="+url(schema),"--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.datasource.username=postgres","--spring.datasource.password=postgres",
                "--spring.flyway.enabled=true","--spring.flyway.schemas="+schema,
                "--spring.jpa.hibernate.ddl-auto=validate","--grpc.server.port=0","--spring.main.banner-mode=off");
    }
    private String call(int port,String patient,String name) throws Exception {
        var channel=ManagedChannelBuilder.forAddress("127.0.0.1",port).usePlaintext().build();
        try {
            var response=BillingServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS)
                .createBillingAccount(BillingRequest.newBuilder().setPatientId(patient).setName(name).setEmail("test@example.com").build());
            assertTrue(response.getSuccess()); return response.getAccountId();
        } finally { channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS); }
    }
    @Test void restartKeepsAccountAndOriginalSnapshot() throws Exception {
        String schema="restart_"+UUID.randomUUID().toString().replace("-","");
        String patient=UUID.randomUUID().toString(),id; var port=new AtomicInteger();
        try (var first=start(schema,port)) { id=call(port.get(),patient,"Original"); }
        try (var second=start(schema,port)) {
            assertEquals(id,call(port.get(),patient,"Different"));
            var repository=second.getBean(com.pm.billing_service.repository.BillingAccountRepository.class);
            assertEquals(1,repository.count()); assertEquals("Original",repository.findByPatientId(patient).orElseThrow().getName());
        }
    }
    @Test void destroyedDatasourceReturnsInternalWithoutSimulatedSuccess() throws Exception {
        String schema="outage_"+UUID.randomUUID().toString().replace("-",""); var port=new AtomicInteger();
        try (var context=start(schema,port)) {
            context.getBean(com.zaxxer.hikari.HikariDataSource.class).close();
            var failure=assertThrows(StatusRuntimeException.class,()->call(port.get(),UUID.randomUUID().toString(),"Test"));
            // A destroyed pool is an internal lifecycle failure, not a classified transient resource error.
            assertEquals(Status.Code.INTERNAL,failure.getStatus().getCode());
            assertEquals("Billing account could not be created",failure.getStatus().getDescription());
        }
    }
}
