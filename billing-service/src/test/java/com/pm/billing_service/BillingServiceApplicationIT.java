package com.pm.billing_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import net.devh.boot.grpc.server.event.GrpcServerStartedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import billing.BillingRequest;
import billing.BillingServiceGrpc;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import com.pm.billing_service.repository.BillingAccountRepository;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:application-test.properties")
@SpringBootTest
class BillingServiceApplicationIT {

	@Autowired
	private PortCapture grpcPort;

	@Autowired
	private BillingAccountRepository accounts;

	@TestConfiguration(proxyBeanMethods = false)
	static class GrpcTestConfiguration {
		@Bean
		PortCapture portCapture() { return new PortCapture(); }
	}

	static class PortCapture implements ApplicationListener<GrpcServerStartedEvent> {
		private int port;
		@Override
		public void onApplicationEvent(GrpcServerStartedEvent event) { port = event.getPort(); }
	}

	@Test
	void servesBillingOverRealGrpcTransport() throws Exception {
		assertTrue(grpcPort.port > 0);
		var channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort.port)
				.usePlaintext().build();
		try {
			String patientId=UUID.randomUUID().toString();
			var stub=BillingServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
			var response = stub.createBillingAccount(BillingRequest.newBuilder().setPatientId(patientId)
							.setName("Test Patient").setEmail("test@example.com").build());
			assertTrue(response.getSuccess());
			assertNotNull(UUID.fromString(response.getAccountId()));
			var stored=accounts.findByPatientId(patientId).orElseThrow();
			assertEquals(stored.getId().toString(),response.getAccountId());
			var repeated=stub.createBillingAccount(BillingRequest.newBuilder().setPatientId(patientId)
					.setName("Changed").setEmail("changed@example.com").build());
			assertEquals(response.getAccountId(),repeated.getAccountId());
			assertEquals("Test Patient",accounts.findByPatientId(patientId).orElseThrow().getName());
		} finally {
			channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	@ParameterizedTest
	@ValueSource(strings={"patientId","name","email","invalid-email","long-id","long-name","long-email"})
	void invalidGrpcRequestsNeverCreateAccounts(String field) throws Exception {
		var request=BillingRequest.newBuilder().setPatientId(UUID.randomUUID().toString())
				.setName("Test Patient").setEmail("test@example.com");
		switch (field) {
			case "patientId" -> request.setPatientId(" ");
			case "name" -> request.setName("");
			case "email" -> request.setEmail("");
			case "invalid-email" -> request.setEmail("invalid");
			case "long-id" -> request.setPatientId("x".repeat(256));
			case "long-name" -> request.setName("x".repeat(101));
			case "long-email" -> request.setEmail("x".repeat(256)+"@example.com");
		}
		long before=accounts.count();
		var channel=ManagedChannelBuilder.forAddress("127.0.0.1",grpcPort.port).usePlaintext().build();
		try {
			var stub=BillingServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS);
			var failure=assertThrows(StatusRuntimeException.class,()->stub.createBillingAccount(request.build()));
			assertEquals(Status.Code.INVALID_ARGUMENT,failure.getStatus().getCode());
			assertEquals(before,accounts.count());
		} finally { channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS); }
	}

	@Test
	void concurrentRetriesReturnOnePersistedAccount() throws Exception {
		String patient=UUID.randomUUID().toString();
		var channel=ManagedChannelBuilder.forAddress("127.0.0.1",grpcPort.port).usePlaintext().build();
		var start=new java.util.concurrent.CountDownLatch(1);
		try (var pool=java.util.concurrent.Executors.newFixedThreadPool(8)) {
			var jobs=new java.util.ArrayList<java.util.concurrent.Future<String>>();
			for (int i=0;i<8;i++) { jobs.add(pool.submit(()-> {
				start.await();
				return BillingServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10,TimeUnit.SECONDS)
					.createBillingAccount(BillingRequest.newBuilder().setPatientId(patient)
						.setName("Concurrent").setEmail("race@example.com").build()).getAccountId();
			})); }
			start.countDown();
			var ids=new java.util.HashSet<String>();
			for (var job:jobs) { ids.add(job.get(15,TimeUnit.SECONDS)); }
			assertEquals(1,ids.size());
			assertEquals(accounts.findByPatientId(patient).orElseThrow().getId().toString(),ids.iterator().next());
			assertEquals(1,accounts.findAll().stream().filter(account->account.getPatientId().equals(patient)).count());
		} finally { channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS); }
	}

	@Test
	void contextLoads() {
	}

}
