package com.sx.gateway.jwt;

import com.sx.gateway.config.GatewayJwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GatewayJwtVerifierTest {
    private static final String APP_SECRET = "test-app-secret-change-me-at-least-32-bytes!!";

    @Test
    void verifiesAppTokenWithMatchingAudience() {
        GatewayJwtProperties properties = properties();
        String token = token("10001", "app-bff");

        assertEquals("10001", new GatewayJwtVerifier(properties)
                .verifyAndGetSubject(token, "/app/api/v1/orders"));
    }

    @Test
    void rejectsCrossAudienceToken() {
        GatewayJwtProperties properties = properties();
        String token = token("10001", "driver-bff");

        assertThrows(MalformedJwtException.class, () -> new GatewayJwtVerifier(properties)
                .verifyAndGetSubject(token, "/app/api/v1/orders"));
    }

    private static GatewayJwtProperties properties() {
        GatewayJwtProperties properties = new GatewayJwtProperties();
        properties.setSecretApp(APP_SECRET);
        properties.setAudienceCheckEnabled(true);
        properties.setAudienceApp("app-bff");
        return properties;
    }

    private static String token(String subject, String audience) {
        return Jwts.builder()
                .subject(subject)
                .audience().add(audience).and()
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(APP_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
