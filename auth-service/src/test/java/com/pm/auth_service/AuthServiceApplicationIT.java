package com.pm.auth_service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Value;
import com.pm.auth_service.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@AutoConfigureMockMvc
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:application-test.properties")
class AuthServiceApplicationIT {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JwtUtil jwtUtil;

	@Value("${jwt.secret}")
	private String jwtSecret;

    @Test void communicatesOnlyRolesFromVerifiedToken() throws Exception {
        String token=jwtUtil.generateToken("synthetic@example.com",List.of("USER"));
        mockMvc.perform(get("/validate").header("Authorization","Bearer "+token).header("X-Validated-Roles","ADMIN"))
            .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Validated-Roles","USER"));
    }

	@Test
	void validatesTokenIssuedByAuth() throws Exception {
		String token = jwtUtil.generateToken("testuser@test.com", List.of("ADMIN"));
		mockMvc.perform(get("/validate").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "not-a-jwt", "not.a.jwt"})
	void rejectsMalformedOrEmptyJwt(String token) throws Exception {
		mockMvc.perform(get("/validate").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsExpiredTokenWithValidSignature() throws Exception {
		String token = Jwts.builder().subject("testuser@test.com")
				.issuedAt(Date.from(Instant.now().minusSeconds(120)))
				.expiration(Date.from(Instant.now().minusSeconds(60)))
				.signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
				.compact();
		mockMvc.perform(get("/validate").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsTokenSignedWithAnUnrelatedKey() throws Exception {
		String token = Jwts.builder().subject("testuser@test.com")
				.expiration(Date.from(Instant.now().plusSeconds(60)))
				.signWith(Jwts.SIG.HS256.key().build()).compact();
		mockMvc.perform(get("/validate").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void preservesBadRequestForMissingOrWrongAuthorizationScheme() throws Exception {
		mockMvc.perform(get("/validate")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/validate").header("Authorization", "Basic invalid"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void contextLoads() throws Exception {
		try (var connection = dataSource.getConnection()) {
			assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:auth-tests"));
		}
	}

	@Test
	void generatesOpenApiForExistingEndpoints() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openapi").isNotEmpty())
				.andExpect(jsonPath("$.paths['/login'].post").exists())
				.andExpect(jsonPath("$.paths['/validate'].get").exists());
	}

	@Test
	void loginReturnsTokenForSeededUser() throws Exception {
		mockMvc.perform(post("/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "email": "testuser@test.com",
								  "password": "password123"
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty());
	}

	@Test
	void loginRejectsInvalidCredentials() throws Exception {
		mockMvc.perform(post("/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "email": "testuser@test.com",
								  "password": "wrong-password"
								}
								"""))
				.andExpect(status().isUnauthorized());
	}

}
