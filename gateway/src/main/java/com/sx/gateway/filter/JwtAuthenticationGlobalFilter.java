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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Bearer JWT 粗校验（签名、exp、可选 aud）；通过后注入 {@link StripSpoofedUserHeaderGlobalFilter#USER_ID_HEADER}。
 */
@Component
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationGlobalFilter.class);

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
        String path = request.getURI().getPath();
        if (isPublic(path, request.getMethod())) {
            return chain.filter(exchange);
        }

        if (!jwtProps.isRequireAuth()) {
            // 开发态关闭强制鉴权时仍要能从 Bearer 注入 X-User-Id，否则 BFF 只认 Header 会误判「未登录」
            return forwardWithOptionalUserId(exchange, chain);
        }

        String raw = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (raw == null || raw.isBlank() || !raw.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            log.warn("JWT 缺失或非 Bearer path={}", path);
            return unauthorized(exchange, "缺少或非法的 Authorization");
        }
        String token = raw.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            log.warn("JWT 空 token path={}", path);
            return unauthorized(exchange, "缺少或非法的 Authorization");
        }
        try {
            String sub = verifier.verifyAndGetSubject(token, path);
            ServerHttpRequest mutated = request.mutate()
                    .header(StripSpoofedUserHeaderGlobalFilter.USER_ID_HEADER, sub)
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (ExpiredJwtException e) {
            log.warn("JWT 已过期 path={} msg={}", path, e.getMessage());
            return unauthorized(exchange, "token 已过期");
        } catch (JwtException | IllegalStateException e) {
            log.warn("JWT 无效 path={} msg={}", path, e.getMessage());
            return unauthorized(exchange, "token 无效");
        }
    }

    /**
     * 不强制要求 JWT 时：有合法 Bearer 则注入 {@code X-User-Id}；无 token 或校验失败则原样转发（由下游返回 401 等）。
     */
    private Mono<Void> forwardWithOptionalUserId(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String raw = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (raw == null || raw.isBlank() || !raw.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return chain.filter(exchange);
        }
        String token = raw.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return chain.filter(exchange);
        }
        String path = request.getURI().getPath();
        try {
            String sub = verifier.verifyAndGetSubject(token, path);
            ServerHttpRequest mutated = request.mutate()
                    .header(StripSpoofedUserHeaderGlobalFilter.USER_ID_HEADER, sub)
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (JwtException | IllegalStateException e) {
            return chain.filter(exchange);
        }
    }

    private boolean isPublic(String path, HttpMethod method) {
        // WebSocket 握手常无法带 Authorization，由 driver-api 从 query token 校验
        if (HttpMethod.GET.equals(method) && path.startsWith("/driver/ws/")) {
            return true;
        }
        if (path.equals("/actuator/health") || path.startsWith("/actuator/health/")) {
            return true;
        }
        if (HttpMethod.POST.equals(method) && "/admin/api/v1/auth/login".equals(path)) {
            return true;
        }
        if (HttpMethod.POST.equals(method)) {
            if ("/app/api/v1/auth/login-password".equals(path)) {
                return true;
            }
            if ("/app/api/v1/auth/login-sms".equals(path)) {
                return true;
            }
            if ("/app/api/v1/auth/sms/send".equals(path)) {
                return true;
            }
            if ("/driver/api/v1/auth/sms/send".equals(path)) {
                return true;
            }
            if ("/driver/api/v1/auth/register-sms".equals(path)) {
                return true;
            }
            if ("/driver/api/v1/auth/register-password".equals(path)) {
                return true;
            }
            if ("/driver/api/v1/auth/login-sms".equals(path)) {
                return true;
            }
            if ("/driver/api/v1/auth/login-password".equals(path)) {
                return true;
            }
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
            log.error("序列化 401 响应 JSON 失败", e);
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
