package com.pm.auth_service.service;

import java.util.List;
import java.util.Optional;

import com.pm.auth_service.dto.LoginRequestDTO;
import com.pm.auth_service.util.JwtUtil;
import io.jsonwebtoken.JwtException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserService userService;
    private final JwtUtil jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserService userService, JwtUtil jwtTokenProvider, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
    }

    public Optional<String> authenticate(LoginRequestDTO loginRequestDTO) {
        Optional<String> token = userService.findByEmail(loginRequestDTO.getEmail())
                .filter(user -> passwordEncoder.matches(loginRequestDTO.getPassword(), user.getPassword()))
                .map(user -> jwtTokenProvider.generateToken(user.getEmail(), List.of(user.getRole())));
        return token;
    }

    public Optional<List<String>> verifiedRoles(String token) {
        try { return Optional.of(jwtTokenProvider.verifiedRoles(token)); }
        catch(JwtException | IllegalArgumentException ex) { return Optional.empty(); }
    }

    public boolean validateToken(String token) {
        try {
            jwtTokenProvider.validateToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
