package com.sx.passengerapi.ws;

import com.sx.passengerapi.auth.AppJwtService;
import com.sx.passengerapi.auth.InvalidPassengerSessionException;
import com.sx.passengerapi.auth.PassengerAuthDecisionService;
import com.sx.passengerapi.auth.PassengerAuthMetrics;
import com.sx.passengerapi.auth.ParsedPassengerJwt;
import com.sx.passengerapi.client.PassengerCoreAuthStateClient;
import com.sx.passengerapi.client.dto.InternalAuthStateResponse;
import com.sx.passengerapi.common.vo.ResponseVo;
import feign.FeignException;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.time.Duration;

import static com.sx.passengerapi.auth.PassengerSessionScope.LIFECYCLE_RESTRICTED;

@Component
@Slf4j
public class PassengerWsHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_CUSTOMER_ID = "customerId";
    public static final String ATTR_REGISTRATION_PERMIT = "registrationPermit";

    private final PassengerWsProperties wsProperties;
    private final AppJwtService jwtService;
    private final PassengerCoreAuthStateClient authStateClient;
    private final PassengerAuthDecisionService decisionService;
    private final PassengerWsSessionRegistry registry;
    private final PassengerAuthMetrics metrics;

    @Autowired
    public PassengerWsHandshakeInterceptor(PassengerWsProperties wsProperties,
                                           AppJwtService jwtService,
                                           PassengerCoreAuthStateClient authStateClient,
                                           PassengerAuthDecisionService decisionService,
                                           PassengerWsSessionRegistry registry,
                                           PassengerAuthMetrics metrics) {
        this.wsProperties = wsProperties;
        this.jwtService = jwtService;
        this.authStateClient = authStateClient;
        this.decisionService = decisionService;
        this.registry = registry;
        this.metrics = metrics;
    }

    public PassengerWsHandshakeInterceptor(PassengerWsProperties wsProperties, AppJwtService jwtService,
                                           PassengerCoreAuthStateClient authStateClient,
                                           PassengerAuthDecisionService decisionService,
                                           PassengerWsSessionRegistry registry) {
        this(wsProperties, jwtService, authStateClient, decisionService, registry,
                new PassengerAuthMetrics());
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        if (!wsProperties.isEnabled()) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        ParsedPassengerJwt parsed = null;
        InternalAuthStateResponse authoritativeState = null;
        try {
            String token = extractToken(request);
            parsed = jwtService.parseAndVerify(token);
            PassengerWsSessionRegistry.RegistrationPermit permit =
                    registry.captureRegistration(parsed.customerId());
            long queryStartedAt = System.nanoTime();
            ResponseVo<InternalAuthStateResponse> result;
            try {
                result = authStateClient.get(parsed.customerId());
            } catch (FeignException ex) {
                metrics.authStateQuery(Duration.ofNanos(System.nanoTime() - queryStartedAt),
                        PassengerAuthMetrics.AuthStateResult.UNAVAILABLE);
                throw ex;
            }
            if (result == null || !Objects.equals(result.getCode(), HttpStatus.OK.value())
                    || result.getData() == null) {
                metrics.authStateQuery(Duration.ofNanos(System.nanoTime() - queryStartedAt),
                        PassengerAuthMetrics.AuthStateResult.INVALID_RESPONSE);
                response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                return false;
            }
            metrics.authStateQuery(Duration.ofNanos(System.nanoTime() - queryStartedAt),
                    PassengerAuthMetrics.AuthStateResult.SUCCESS);
            authoritativeState = result.getData();

            if (parsed.scope() == LIFECYCLE_RESTRICTED) {
                decisionService.verify(parsed, authoritativeState, 1);
                metrics.jwtRejected(PassengerAuthMetrics.JwtRejectReason.RESTRICTED);
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }

            decisionService.verify(parsed, authoritativeState, 2);
            attributes.put(ATTR_CUSTOMER_ID, parsed.customerId());
            attributes.put(ATTR_REGISTRATION_PERMIT, permit);
            return true;
        } catch (InvalidPassengerSessionException e) {
            metrics.jwtRejected(rejectReason(parsed, authoritativeState));
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            metrics.jwtRejected(PassengerAuthMetrics.JwtRejectReason.MALFORMED);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        } catch (FeignException e) {
            metrics.jwtRejected(PassengerAuthMetrics.JwtRejectReason.AUTH_STATE_UNAVAILABLE);
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        } catch (Exception e) {
            log.warn("WS handshake authentication failed type={}", e.getClass().getSimpleName());
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
    }

    private static PassengerAuthMetrics.JwtRejectReason rejectReason(
            ParsedPassengerJwt token,
            InternalAuthStateResponse state) {
        return token != null && state != null && token.authEpoch() != state.getAuthEpoch()
                ? PassengerAuthMetrics.JwtRejectReason.EPOCH_MISMATCH
                : PassengerAuthMetrics.JwtRejectReason.STATE_MISMATCH;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }

    private static String extractToken(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest sreq) {
            String header = sreq.getServletRequest().getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                return header.substring("Bearer ".length()).trim();
            }
        }
        URI uri = request.getURI();
        String q = uri == null ? null : uri.getQuery();
        if (q == null || q.isBlank()) {
            throw new IllegalArgumentException("missing token");
        }
        for (String part : q.split("&")) {
            int idx = part.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String k = part.substring(0, idx);
            String v = part.substring(idx + 1);
            if ("token".equals(k) && v != null && !v.isBlank()) {
                return java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("missing token");
    }
}
