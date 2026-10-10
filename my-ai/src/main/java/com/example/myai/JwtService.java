package com.example.myai;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    @Value("${security.jwt.secret}")
    private String secret;

    @Value("${security.jwt.expiration-ms:86400000}")
    private long expirationMs;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("security.jwt.secret must be configured and must not be blank");
        }

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("security.jwt.secret must be at least 32 bytes");
        }

        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(String username, String displayName) {
        return generateToken(username, displayName, "USER");
    }

    public String generateToken(String username, String displayName, String role) {
        Instant now = Instant.now();
        Instant expireAt = now.plusMillis(expirationMs);
        return Jwts.builder()
                .subject(username)
                .claim("displayName", displayName)
                .claim("role", role == null || role.isBlank() ? "USER" : role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expireAt))
                .signWith(secretKey)
                .compact();
    }

    public JwtPrincipal parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new JwtPrincipal(
                claims.getSubject(),
                claims.get("displayName", String.class),
                claims.get("role", String.class)
        );
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public record JwtPrincipal(String username, String displayName, String role) {
    }
}
