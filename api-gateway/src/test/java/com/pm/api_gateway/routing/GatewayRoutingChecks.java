package com.pm.api_gateway.routing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class GatewayRoutingChecks {
    @LocalServerPort
    private int gatewayPort;
    private final HttpClient client = HttpClient.newHttpClient();
    abstract StubBackend backend();

    private HttpResponse<String> request(String method, String path, String authorization, String body)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
                .timeout(Duration.ofSeconds(6));
        if (authorization != null) { request.header("Authorization", authorization); }
        if (body != null) { request.header("Content-Type", "application/json"); }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void doesNotReplayPostWhenPatientConnectionIsLost() throws Exception {
        int before=backend().patientCalls.get();
        assertEquals(503,request("POST","/api/patients?disconnect=true","Bearer accepted-token","{}").statusCode());
        assertEquals(before+1,backend().patientCalls.get(),"A lost HTTP response must not automatically replay POST");
    }

    @Test
    void forwardsLoginToAuthLoginWithoutRequiringToken() throws Exception {
        int validationsBefore = backend().validationCalls.get();
        var response = request("POST", "/api/auth/login", null,
                "{\"email\":\"test@example.com\",\"password\":\"test-password\"}");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"token\""));
        assertEquals(validationsBefore, backend().validationCalls.get());
    }

    @Test
    void preservesUnauthorizedLoginResponse() throws Exception {
        assertEquals(401, request("POST", "/api/auth/login", null,
                "{\"email\":\"test@example.com\",\"password\":\"wrong-password\"}").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"slow-password", "disconnect-password"})
    void loginFailureReturnsBadGatewayWithinLimit(String password) throws Exception {
        long start = System.nanoTime();
        var response = request("POST", "/api/auth/login", null,
                "{\"email\":\"test@example.com\",\"password\":\"" + password + "\"}");
        assertEquals(502, response.statusCode());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(4)) < 0);
    }

    @Test
    void validatesThenForwardsPatientsWithQueryString() throws Exception {
        int patientsBefore = backend().patientCalls.get();
        var response = request("GET", "/api/patients?limit=1", "Bearer accepted-token", null);
        assertEquals(200, response.statusCode());
        assertEquals("[]", response.body());
        assertEquals("/patients?limit=1", backend().patientUri.get());
        assertEquals("Bearer accepted-token", backend().validationAuthorization.get());
        assertEquals(patientsBefore + 1, backend().patientCalls.get());
    }

    @Test
    void missingTokenDoesNotReachEitherBackend() throws Exception {
        int patientsBefore = backend().patientCalls.get();
        int validationsBefore = backend().validationCalls.get();
        assertEquals(401, request("GET", "/api/patients", null, null).statusCode());
        assertEquals(patientsBefore, backend().patientCalls.get());
        assertEquals(validationsBefore, backend().validationCalls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-token", "expired-token"})
    void rejectedValidationNeverReachesPatient(String token) throws Exception {
        int patientsBefore = backend().patientCalls.get();
        assertEquals(401, request("GET", "/api/patients", "Bearer " + token, null).statusCode());
        assertEquals(patientsBefore, backend().patientCalls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"auth-error", "auth-disconnected", "auth-slow"})
    void authFailureIsBoundedAndNeverReachesPatient(String token) throws Exception {
        int patientsBefore = backend().patientCalls.get();
        long start = System.nanoTime();
        assertEquals(502, request("GET", "/api/patients", "Bearer " + token, null).statusCode());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(4)) < 0);
        assertEquals(patientsBefore, backend().patientCalls.get());
    }
}
