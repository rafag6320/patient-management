package com.pm.patient_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.pm.patient_service.grpc.BillingServiceGrpcClient;
import com.pm.patient_service.kafka.KafkaProducer;

@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:application-test.properties")
@SpringBootTest
class PatientServiceApplicationIT {

	@Autowired
	private DataSource dataSource;

	@MockitoBean
	private BillingServiceGrpcClient billingClient;
	@MockitoBean
	private KafkaProducer kafkaProducer;

	@Test
	void contextLoads() throws Exception {
		try (var connection = dataSource.getConnection()) {
			assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:patient-tests"));
		}
	}

}
