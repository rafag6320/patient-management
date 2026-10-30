package com.pm.auth_service.util;

import java.util.Date;
import java.util.List;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;

@Component
public class JwtUtil {

    private final SecretKey secretKey;

    public JwtUtil(@Value("${jwt.secret}") String secret) {
        secretKey = Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String generateToken(String email, List<String> roles) {
        return Jwts.builder()
                .setSubject(email)
                .claim("roles", roles)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 86400000))
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public List<String> verifiedRoles(String token) {
        Object roles=Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload().get("roles");
        if(!(roles instanceof List<?> list)) { return List.of(); }
        return list.stream().filter(String.class::isInstance).map(String.class::cast)
            .filter(role->role.matches("[A-Z_]{1,32}")).distinct().toList();
    }

    public void validateToken(String token) throws JwtException {
        Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token);
    }
}