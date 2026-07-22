package com.sx.passengerapi.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passengerapi.client.PassengerCoreAuthStateClient;
import com.sx.passengerapi.client.dto.InternalAuthStateResponse;
import com.sx.passengerapi.common.vo.ResponseVo;
import feign.FeignException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.time.Duration;

import static com.sx.passengerapi.auth.PassengerSessionScope.LIFECYCLE_RESTRICTED;

/**
 * 校验乘客 JWT 签名、aud 及 passenger DB 权威认证状态；通过后注入可信身份头。
 * 公开路径：登录、发短信；{@code POST /app/api/v1/auth/logout} 须鉴权。
 */
@Component
@Order(5)
public class PassengerJwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final Set<String> RESTRICTED_LIFECYCLE_PATHS = Set.of();

    private final AppJwtService jwtService;
    private final PassengerCoreAuthStateClient authStateClient;
    private final PassengerAuthDecisionService decisionService;
    private final ObjectMapper objectMapper;
    private final PassengerAuthMetrics metrics;

    @Autowired
    public PassengerJwtAuthFilter(
            AppJwtService jwtService,
            PassengerCoreAuthStateClient authStateClient,
            PassengerAuthDecisionService decisionService,
            ObjectMapper objectMapper,
            PassengerAuthMetrics metrics) {
        this.jwtService = jwtService;
        this.authStateClient = authStateClient;
        this.decisionService = decisionService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public PassengerJwtAuthFilter(AppJwtService jwtService, PassengerCoreAuthStateClient authStateClient,
                                  PassengerAuthDecisionService decisionService, ObjectMapper objectMapper) {
        this(jwtService, authStateClient, decisionService, objectMapper, new PassengerAuthMetrics());
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
            metrics.jwtRejected(PassengerAuthMetrics.JwtRejectReason.MISSING);
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "缺少或非法的 Authorization");
            return;
        }
        String token = raw.substring(BEARER.length()).trim();
        if (token.isEmpty()) {
            metrics.jwtRejected(PassengerAuthMetrics.JwtRejectReason.MISSING);
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "缺少或非法的 Authorization");
            return;
        }

        final ParsedPassengerJwt parsed;
        try {
            parsed = jwtService.parseAndVerify(token);
        } catch (Exception e) {
            metrics.jwtRejected(PassengerAuthMetrics.JwtRejectReason.MALFORMED);
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "token 无效");
            return;
        }

        final PassengerAuthContext authContext;
        long queryStartedAt = System.nanoTime();
        try {
            ResponseVo<InternalAuthStateResponse> result = authStateClient.get(parsed.customerId());
            if (result == null || !Objects.equals(result.getCode(), HttpServletResponse.SC_OK)
                    || result.getData() == null) {
                metrics.authStateQuery(Duration.ofNanos(System.nanoTime() - queryStartedAt),
                        PassengerAuthMetrics.AuthStateResult.INVALID_RESPONSE);
                writeJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "认证服务暂时不可用");
                return;
            }
            metrics.authStateQuery(Duration.ofNanos(System.nanoTime() - queryStartedAt),
                    PassengerAuthMetrics.AuthStateResult.SUCCESS);
            authContext = decisionService.verify(parsed, result.getData(), 1);
        } catch (InvalidPassengerSessionException e) {
            metrics.jwtRejected(PassengerAuthMetrics.JwtRejectReason.STATE_MISMATCH);
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "登录已失效，请重新登录");
            return;
        } catch (FeignException e) {
            metrics.authStateQuery(Duration.ofNanos(System.nanoTime() - queryStartedAt),
                    PassengerAuthMetrics.AuthStateResult.UNAVAILABLE);
            metrics.jwtRejected(PassengerAuthMetrics.JwtRejectReason.AUTH_STATE_UNAVAILABLE);
            writeJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "认证服务暂时不可用");
            return;
        }

        if (authContext.scope() == LIFECYCLE_RESTRICTED && !isRestrictedLifecyclePath(path)) {
            metrics.jwtRejected(PassengerAuthMetrics.JwtRejectReason.RESTRICTED);
            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "受限会话不可访问该资源");
            return;
        }

        filterChain.doFilter(new PassengerAuthRequestWrapper(request, authContext), response);
    }

    private static boolean isPublicAuth(String path, String method) {
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        return "/app/api/v1/auth/sms/send".equals(path)
                || "/app/api/v1/auth/login-sms".equals(path)
                || "/app/api/v1/auth/login-password".equals(path);
    }

    private static boolean isRestrictedLifecyclePath(String path) {
        return RESTRICTED_LIFECYCLE_PATHS.contains(path);
    }

    private void writeJsonError(HttpServletResponse response, int httpStatus, String msg) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("code", httpStatus, "msg", msg)));
    }
}
