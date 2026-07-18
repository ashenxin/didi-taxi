package com.sx.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.gateway.config.GatewayJwtProperties;
import com.sx.gateway.jwt.GatewayJwtVerifier;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewaySecurityFiltersTest {

    private static final String APP_SECRET = "gateway-filter-app-secret-at-least-32-bytes!!";

    @Test
    void removesSpoofedUserIdBeforeInjectingVerifiedSubject() {
        GatewayJwtProperties properties = secureProperties();
        String token = token("10001", "app-bff");
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/app/api/v1/orders")
                .header(StripSpoofedUserHeaderGlobalFilter.USER_ID_HEADER, "attacker")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        applySecurityFilters(exchange, properties, capture(forwarded)).block();

        assertNotNull(forwarded.get());
        assertEquals(List.of("10001"), forwarded.get().getRequest().getHeaders()
                .get(StripSpoofedUserHeaderGlobalFilter.USER_ID_HEADER));
        assertTrue(new StripSpoofedUserHeaderGlobalFilter().getOrder()
                < authenticationFilter(properties).getOrder());
    }

    @Test
    void rejectsProtectedPathWithoutBearerToken() {
        GatewayJwtProperties properties = secureProperties();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/app/api/v1/orders"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        applySecurityFilters(exchange, properties, capture(forwarded)).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals("application/json", exchange.getResponse().getHeaders().getContentType().toString());
        assertTrue(exchange.getResponse().getBodyAsString().block().contains("Authorization"));
        assertNull(forwarded.get());
    }

    @Test
    void allowsPublicLoginWebsocketAndOptionsWithoutToken() {
        GatewayJwtProperties properties = secureProperties();

        assertForwardedWithoutTrustedUser(properties,
                MockServerHttpRequest.post("/app/api/v1/auth/login-sms").build());
        assertForwardedWithoutTrustedUser(properties,
                MockServerHttpRequest.get("/app/ws/v1/stream").build());
        assertForwardedWithoutTrustedUser(properties,
                MockServerHttpRequest.method(HttpMethod.OPTIONS, "/app/api/v1/orders").build());
    }

    @Test
    void removesSpoofedUserIdFromPublicPath() {
        GatewayJwtProperties properties = secureProperties();
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/app/api/v1/auth/login-sms")
                .header(StripSpoofedUserHeaderGlobalFilter.USER_ID_HEADER, "attacker"));

        applySecurityFilters(exchange, properties, capture(forwarded)).block();

        assertNotNull(forwarded.get());
        assertFalse(forwarded.get().getRequest().getHeaders()
                .containsKey(StripSpoofedUserHeaderGlobalFilter.USER_ID_HEADER));
    }

    @Test
    void optionalAuthInjectsOnlyVerifiedBearerSubject() {
        GatewayJwtProperties properties = secureProperties();
        properties.setRequireAuth(false);
        AtomicReference<ServerWebExchange> validForwarded = new AtomicReference<>();
        MockServerWebExchange valid = MockServerWebExchange.from(MockServerHttpRequest
                .get("/app/api/v1/orders")
                .header(StripSpoofedUserHeaderGlobalFilter.USER_ID_HEADER, "attacker")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("10002", "app-bff")));

        applySecurityFilters(valid, properties, capture(validForwarded)).block();

        assertEquals("10002", validForwarded.get().getRequest().getHeaders()
                .getFirst(StripSpoofedUserHeaderGlobalFilter.USER_ID_HEADER));

        AtomicReference<ServerWebExchange> invalidForwarded = new AtomicReference<>();
        MockServerWebExchange invalid = MockServerWebExchange.from(MockServerHttpRequest
                .get("/app/api/v1/orders")
                .header(StripSpoofedUserHeaderGlobalFilter.USER_ID_HEADER, "attacker")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"));

        applySecurityFilters(invalid, properties, capture(invalidForwarded)).block();

        assertNotNull(invalidForwarded.get());
        assertFalse(invalidForwarded.get().getRequest().getHeaders()
                .containsKey(StripSpoofedUserHeaderGlobalFilter.USER_ID_HEADER));
    }

    private static void assertForwardedWithoutTrustedUser(
            GatewayJwtProperties properties,
            MockServerHttpRequest request) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        applySecurityFilters(MockServerWebExchange.from(request), properties, capture(forwarded)).block();

        assertNotNull(forwarded.get());
        assertFalse(forwarded.get().getRequest().getHeaders()
                .containsKey(StripSpoofedUserHeaderGlobalFilter.USER_ID_HEADER));
    }

    private static Mono<Void> applySecurityFilters(
            ServerWebExchange exchange,
            GatewayJwtProperties properties,
            GatewayFilterChain terminal) {
        StripSpoofedUserHeaderGlobalFilter strip = new StripSpoofedUserHeaderGlobalFilter();
        JwtAuthenticationGlobalFilter authentication = authenticationFilter(properties);
        return strip.filter(exchange, stripped -> authentication.filter(stripped, terminal));
    }

    private static JwtAuthenticationGlobalFilter authenticationFilter(GatewayJwtProperties properties) {
        return new JwtAuthenticationGlobalFilter(
                new GatewayJwtVerifier(properties), properties, new ObjectMapper());
    }

    private static GatewayFilterChain capture(AtomicReference<ServerWebExchange> forwarded) {
        return exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        };
    }

    private static GatewayJwtProperties secureProperties() {
        GatewayJwtProperties properties = new GatewayJwtProperties();
        properties.setSecretApp(APP_SECRET);
        properties.setAudienceCheckEnabled(true);
        properties.setAudienceApp("app-bff");
        properties.setRequireAuth(true);
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
