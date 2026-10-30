package com.pm.analytics_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:application-test.properties")
@SpringBootTest
class AnalyticsServiceApplicationIT {

	@Autowired
	private KafkaListenerEndpointRegistry listeners;

	@Test
	void contextLoads() {
		listeners.getListenerContainers().forEach(container -> assertFalse(container.isRunning()));
	}

}
