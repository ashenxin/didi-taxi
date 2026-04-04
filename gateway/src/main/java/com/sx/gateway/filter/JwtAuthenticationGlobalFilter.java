package com.sx.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.gateway.config.GatewayJwtProperties;
import com.sx.gateway.jwt.GatewayJwtVerifier;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Bearer JWT 粗校验（签名、exp、可选 aud）；通过后注入 {@link StripSpoofedUserHeaderGlobalFilter#USER_ID_HEADER}。
 */
@Component
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";

    private final GatewayJwtVerifier verifier;
    private final GatewayJwtProperties jwtProps;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationGlobalFilter(
            GatewayJwtVerifier verifier,
            GatewayJwtProperties jwtProps,
            ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.jwtProps = jwtProps;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (HttpMethod.OPTIONS.equals(request.getMethod())) {
            return chain.filter(exchange);
        }
        if (!jwtProps.isRequireAuth()) {
            return chain.filter(exchange);
        }
        String path = request.getURI().getPath();
        if (isPublic(path, request.getMethod())) {
            return chain.filter(exchange);
        }

        String raw = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (raw == null || raw.isBlank() || !raw.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return unauthorized(exchange, "缺少或非法的 Authorization");
        }
        String token = raw.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return unauthorized(exchange, "缺少或非法的 Authorization");
        }
        try {
            String sub = verifier.verifyAndGetSubject(token, path);
            ServerHttpRequest mutated = request.mutate()
                    .header(StripSpoofedUserHeaderGlobalFilter.USER_ID_HEADER, sub)
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (ExpiredJwtException e) {
            return unauthorized(exchange, "token 已过期");
        } catch (JwtException | IllegalStateException e) {
            return unauthorized(exchange, "token 无效");
        }
    }

    private boolean isPublic(String path, HttpMethod method) {
        if (path.equals("/actuator/health") || path.startsWith("/actuator/health/")) {
            return true;
        }
        if (HttpMethod.POST.equals(method) && "/admin/api/v1/auth/login".equals(path)) {
            return true;
        }
        return false;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String msg) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(Map.of("code", 401, "msg", msg));
        } catch (JsonProcessingException e) {
            body = "{\"code\":401,\"msg\":\"Unauthorized\"}".getBytes();
        }
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
