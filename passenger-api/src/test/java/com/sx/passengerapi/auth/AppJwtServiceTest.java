package com.sx.passengerapi.auth;

import com.sx.passengerapi.config.AppJwtProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import static com.sx.passengerapi.auth.PassengerSessionScope.LIFECYCLE_RESTRICTED;
import static com.sx.passengerapi.auth.PassengerSessionScope.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppJwtServiceTest {

    private static final String SECRET = "test-app-jwt-secret-with-at-least-32-bytes";
    private static final String AUDIENCE = "app-bff";

    private AppJwtProperties properties;
    private AppJwtService jwt;

    @BeforeEach
    void setUp() {
        properties = new AppJwtProperties();
        properties.setSecret(SECRET);
        properties.setAudience(AUDIENCE);
        properties.setExpirationSeconds(600);
        properties.setRestrictedExpirationSeconds(120);
        jwt = new AppJwtService(properties);
    }

    @Test
    void roundTripsNormalAndRestrictedClaims() {
        String normal = jwt.createPassengerToken(7L, "13800138000", 5L, NORMAL, 1, null);
        assertThat(jwt.parseAndVerify(normal)).isEqualTo(
                new ParsedPassengerJwt(7L, "13800138000", 5L, NORMAL, 1, null));

        String restricted = jwt.createPassengerToken(
                7L, "13800138000", 6L, LIFECYCLE_RESTRICTED, 1, "op-1");
        assertThat(jwt.parseAndVerify(restricted)).isEqualTo(
                new ParsedPassengerJwt(7L, "13800138000", 6L, LIFECYCLE_RESTRICTED, 1, "op-1"));
    }

    @Test
    void rejectsLegacyTvAndMissingStrictClaims() {
        assertThatThrownBy(() -> jwt.parseAndVerify(signClaims(Map.of("tv", 1, "audit", 1))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwt.parseAndVerify(signClaims(Map.of("ae", 1, "audit", 1))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwt.parseAndVerify(signClaims(Map.of("ae", 1, "scope", "NORMAL"))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsUnexpectedClaimTypesAndNonPositiveEpoch() {
        assertThatThrownBy(() -> jwt.parseAndVerify(
                signClaims(Map.of("ae", "5", "scope", "NORMAL", "audit", 1))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwt.parseAndVerify(
                signClaims(Map.of("ae", 5, "scope", 1, "audit", 1))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwt.parseAndVerify(
                signClaims(Map.of("ae", 5, "scope", "NORMAL", "audit", "1"))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwt.parseAndVerify(
                signClaims(Map.of("ae", 0, "scope", "NORMAL", "audit", 1))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsInvalidScopeAuditOperationCombinations() {
        assertThatThrownBy(() -> jwt.createPassengerToken(7L, "", 6L, LIFECYCLE_RESTRICTED, 2, "op-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwt.createPassengerToken(7L, "", 6L, LIFECYCLE_RESTRICTED, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwt.createPassengerToken(7L, "", 6L, NORMAL, 1, "op-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwt.createPassengerToken(7L, "", 6L, NORMAL, 3, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidCombinationsDuringParsing() {
        assertThatThrownBy(() -> jwt.parseAndVerify(
                signClaims(Map.of("ae", 5, "scope", "LIFECYCLE_RESTRICTED", "audit", 2,
                        "operationNo", "op-1"))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwt.parseAndVerify(
                signClaims(Map.of("ae", 5, "scope", "NORMAL", "audit", 1,
                        "operationNo", "op-1"))))
                .isInstanceOf(JwtException.class);
    }

    private String signClaims(Map<String, Object> claims) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject("7")
                .claims(claims)
                .audience().add(AUDIENCE).and()
                .issuedAt(new Date(now))
                .expiration(new Date(now + 60_000))
                .signWith(signingKey())
                .compact();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }
}
