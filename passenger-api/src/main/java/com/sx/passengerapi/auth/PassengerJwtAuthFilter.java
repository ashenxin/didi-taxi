package com.sx.passengerapi.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 校验乘客 JWT 签名、aud、{@code tv} 与 Redis 当前版本一致；通过后注入 {@code X-User-Id}。
 * 公开路径：登录、发短信；{@code POST /app/api/v1/auth/logout} 须鉴权。
 */
@Component
@Order(5)
public class PassengerJwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final AppJwtService jwtService;
    private final PassengerTokenVersionStore tokenVersionStore;
    private final ObjectMapper objectMapper;

    public PassengerJwtAuthFilter(
            AppJwtService jwtService,
            PassengerTokenVersionStore tokenVersionStore,
            ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.tokenVersionStore = tokenVersionStore;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/app/api/v1");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (path.startsWith("/actuator/")) {
            filterChain.doFilter(request, response);
            return;
        }
        if (isPublicAuth(path, request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String raw = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (raw == null || !raw.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "缺少或非法的 Authorization");
            return;
        }
        String token = raw.substring(BEARER.length()).trim();
        if (token.isEmpty()) {
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "缺少或非法的 Authorization");
            return;
        }

        final ParsedPassengerJwt parsed;
        try {
            parsed = jwtService.parseAndVerify(token);
        } catch (Exception e) {
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "token 无效");
            return;
        }

        Long cur = tokenVersionStore.currentVersion(parsed.customerId());
        if (cur == null || cur.longValue() != parsed.tokenVersion()) {
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "登录已失效，请重新登录");
            return;
        }

        filterChain.doFilter(new PassengerAuthRequestWrapper(request, parsed.customerId()), response);
    }

    private static boolean isPublicAuth(String path, String method) {
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        return "/app/api/v1/auth/sms/send".equals(path)
                || "/app/api/v1/auth/login-sms".equals(path)
                || "/app/api/v1/auth/login-password".equals(path);
    }

    private void writeJsonError(HttpServletResponse response, int httpStatus, String msg) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("code", httpStatus, "msg", msg)));
    }
}
