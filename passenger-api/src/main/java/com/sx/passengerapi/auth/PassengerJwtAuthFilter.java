package com.sx.passengerapi.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passengerapi.auth.action.PassengerActionCode;
import com.sx.passengerapi.auth.action.PassengerActionDecision;
import com.sx.passengerapi.auth.action.PassengerActionPolicy;
import com.sx.passengerapi.auth.action.PassengerActionResolver;
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
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.Optional;
import java.time.Duration;

/**
 * 校验乘客 JWT 签名、aud 及 passenger DB 权威认证状态；通过后注入可信身份头。
 * 公开路径：登录、发短信；{@code POST /app/api/v1/auth/logout} 须鉴权。
 */
@Component
@Order(5)
public class PassengerJwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final PathPattern ACTUATOR_ROOT = pathPattern("/actuator");
    private static final PathPattern ACTUATOR_DESCENDANTS = pathPattern("/actuator/**");
    private static final PathPattern INTERNAL_ROOT = pathPattern("/app/internal");
    private static final PathPattern INTERNAL_DESCENDANTS = pathPattern("/app/internal/**");
    private static final PathPattern WS_ROOT = pathPattern("/app/ws");
    private static final PathPattern WS_DESCENDANTS = pathPattern("/app/ws/**");
    private static final List<PathPattern> PUBLIC_AUTH_PATHS = List.of(
            pathPattern("/app/api/v1/auth/sms/send"),
            pathPattern("/app/api/v1/auth/login-sms"),
            pathPattern("/app/api/v1/auth/login-password"));
    private final AppJwtService jwtService;
    private final PassengerCoreAuthStateClient authStateClient;
    private final PassengerAuthDecisionService decisionService;
    private final ObjectMapper objectMapper;
    private final PassengerAuthMetrics metrics;
    private final PassengerActionResolver actionResolver;
    private final PassengerActionPolicy actionPolicy;

    @Autowired
    public PassengerJwtAuthFilter(
            AppJwtService jwtService,
            PassengerCoreAuthStateClient authStateClient,
            PassengerAuthDecisionService decisionService,
            ObjectMapper objectMapper,
            PassengerAuthMetrics metrics,
            PassengerActionResolver actionResolver,
            PassengerActionPolicy actionPolicy) {
        this.jwtService = jwtService;
        this.authStateClient = authStateClient;
        this.decisionService = decisionService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.actionResolver = actionResolver;
        this.actionPolicy = actionPolicy;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        PathContainer path = pathWithinApplication(request);
        return ACTUATOR_ROOT.matches(path)
                || ACTUATOR_DESCENDANTS.matches(path)
                || INTERNAL_ROOT.matches(path)
                || INTERNAL_DESCENDANTS.matches(path)
                || WS_ROOT.matches(path)
                || WS_DESCENDANTS.matches(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        PathContainer path = pathWithinApplication(request);
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
        InternalAuthStateResponse authoritativeState = null;
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
            authoritativeState = result.getData();
            authContext = decisionService.verify(parsed, authoritativeState, 1);
        } catch (InvalidPassengerSessionException e) {
            metrics.jwtRejected(PassengerSessionRejectionClassifier.classify(parsed, authoritativeState));
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "登录已失效，请重新登录");
            return;
        } catch (FeignException e) {
            metrics.authStateQuery(Duration.ofNanos(System.nanoTime() - queryStartedAt),
                    PassengerAuthMetrics.AuthStateResult.UNAVAILABLE);
            metrics.jwtRejected(PassengerAuthMetrics.JwtRejectReason.AUTH_STATE_UNAVAILABLE);
            writeJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "认证服务暂时不可用");
            return;
        }

        Optional<PassengerActionCode> action = actionResolver.resolve(request.getMethod(), path);
        if (action.isEmpty()) {
            metrics.actionDecision(null, PassengerActionDecision.UNKNOWN);
            writeJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "暂时无法确认该操作权限");
            return;
        }
        PassengerActionDecision actionDecision = actionPolicy.decide(
                authoritativeState.getBusinessStatus(), authoritativeState.getLifecycleStatus(),
                authContext.scope(), action.get());
        metrics.actionDecision(action.get(), actionDecision);
        if (actionDecision == PassengerActionDecision.UNKNOWN) {
            writeJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "暂时无法确认该操作权限");
            return;
        }
        if (actionDecision == PassengerActionDecision.DENY) {
            metrics.jwtRejected(PassengerAuthMetrics.JwtRejectReason.RESTRICTED);
            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "受限会话不可访问该资源");
            return;
        }

        filterChain.doFilter(new PassengerAuthRequestWrapper(request, authContext), response);
    }

    private static boolean isPublicAuth(PathContainer path, String method) {
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        return PUBLIC_AUTH_PATHS.stream().anyMatch(pattern -> pattern.matches(path));
    }

    private static PathContainer pathWithinApplication(HttpServletRequest request) {
        return ServletRequestPathUtils.parseAndCache(request).pathWithinApplication();
    }

    private static PathPattern pathPattern(String pattern) {
        return PathPatternParser.defaultInstance.parse(pattern);
    }

    private void writeJsonError(HttpServletResponse response, int httpStatus, String msg) throws IOException {
        response.setStatus(httpStatus);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("code", httpStatus, "msg", msg)));
    }
}
