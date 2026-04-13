package com.sx.driverapi.auth;

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
 * 校验司机 JWT 签名、aud、{@code tv} 与 Redis 当前版本一致；按路径区分 {@code audit}（HTTP=1，WS=2）。
 */
@Component
@Order(5)
public class DriverJwtValidationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final DriverJwtService jwtService;
    private final DriverTokenVersionStore tokenVersionStore;
    private final ObjectMapper objectMapper;

    public DriverJwtValidationFilter(DriverJwtService jwtService,
                                     DriverTokenVersionStore tokenVersionStore,
                                     ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.tokenVersionStore = tokenVersionStore;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/driver/");
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

        String token = resolveToken(request);
        if (token == null || token.isBlank()) {
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "缺少或非法的 Authorization");
            return;
        }
        final ParsedDriverJwt parsed;
        try {
            parsed = jwtService.parseAndVerify(token.trim());
        } catch (Exception e) {
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "token 无效");
            return;
        }

        Long cur = tokenVersionStore.currentVersion(parsed.driverId());
        if (cur == null || cur.longValue() != parsed.tokenVersion()) {
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "登录已失效，请重新登录");
            return;
        }

        boolean wsHandshake = path.startsWith("/driver/ws");
        int expectedAudit = wsHandshake ? 2 : 1;
        if (parsed.audit() != expectedAudit) {
            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "token 渠道不匹配");
            return;
        }

        request.setAttribute(DriverRequestAttributes.DRIVER_ID, parsed.driverId());
        request.setAttribute(DriverRequestAttributes.DRIVER_PHONE, parsed.phone());
        filterChain.doFilter(request, response);
    }

    private static boolean isPublicAuth(String path, String method) {
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        return "/driver/api/v1/auth/sms/send".equals(path)
                || "/driver/api/v1/auth/register-sms".equals(path)
                || "/driver/api/v1/auth/register-password".equals(path)
                || "/driver/api/v1/auth/login-sms".equals(path)
                || "/driver/api/v1/auth/login-password".equals(path);
    }

    private static String resolveToken(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path != null && path.startsWith("/driver/ws")) {
            String q = request.getParameter("token");
            if (q != null && !q.isBlank()) {
                return q.trim();
            }
        }
        String raw = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (raw != null && raw.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return raw.substring(BEARER.length()).trim();
        }
        return null;
    }

    private void writeJsonError(HttpServletResponse response, int httpStatus, String msg) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("code", httpStatus, "msg", msg)));
    }
}
