package com.sx.passengerapi.ws;

import com.sx.passengerapi.auth.AppJwtService;
import com.sx.passengerapi.auth.PassengerAuthDecisionService;
import com.sx.passengerapi.auth.PassengerAuthMetrics;
import com.sx.passengerapi.auth.ParsedPassengerJwt;
import com.sx.passengerapi.client.PassengerCoreAuthStateClient;
import com.sx.passengerapi.client.dto.InternalAuthStateResponse;
import com.sx.passengerapi.common.vo.ResponseVo;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.FeignException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static com.sx.passengerapi.auth.PassengerSessionScope.LIFECYCLE_RESTRICTED;
import static com.sx.passengerapi.auth.PassengerSessionScope.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;

class PassengerWsHandshakeInterceptorTest {

    private final AppJwtService jwtService = mock(AppJwtService.class);
    private final PassengerCoreAuthStateClient authStateClient = mock(PassengerCoreAuthStateClient.class);
    private final PassengerWsSessionRegistry registry = mock(PassengerWsSessionRegistry.class);
    private final PassengerAuthMetrics metrics = mock(PassengerAuthMetrics.class);
    private PassengerWsProperties properties;
    private PassengerWsHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = new PassengerWsProperties();
        properties.setEnabled(true);
        interceptor = new PassengerWsHandshakeInterceptor(
                properties, jwtService, authStateClient, new PassengerAuthDecisionService(), registry, metrics);
    }

    @Test
    void activeNormalWsTokenAllowsAfterExactlyOneAuthorityLookup() {
        PassengerWsSessionRegistry.RegistrationPermit permit = mock(
                PassengerWsSessionRegistry.RegistrationPermit.class);
        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token(NORMAL, 2, null));
        when(registry.captureRegistration(7L)).thenReturn(permit);
        when(authStateClient.get(7L)).thenReturn(ok(state(
                7L, "ACTIVE", 9L, null, "NORMAL", true)));
        Map<String, Object> attributes = new HashMap<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.beforeHandshake(
                request(), serverResponse(response), mock(WebSocketHandler.class), attributes);

        assertThat(allowed).isTrue();
        assertThat(attributes).containsEntry(PassengerWsHandshakeInterceptor.ATTR_CUSTOMER_ID, 7L);
        assertThat(attributes).containsEntry(
                PassengerWsHandshakeInterceptor.ATTR_REGISTRATION_PERMIT, permit);
        var order = org.mockito.Mockito.inOrder(registry, authStateClient);
        order.verify(registry).captureRegistration(7L);
        order.verify(authStateClient).get(7L);
        verify(authStateClient, times(1)).get(7L);
    }

    @Test
    void restrictedAuthoritativeTokenIsForbiddenAfterOneLookup() {
        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token(LIFECYCLE_RESTRICTED, 1, "op-1"));
        when(authStateClient.get(7L)).thenReturn(ok(state(
                7L, "CANCELLING", 9L, "op-1", "LIFECYCLE_RESTRICTED", true)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.beforeHandshake(
                request(), serverResponse(response), mock(WebSocketHandler.class), new HashMap<>());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(authStateClient, times(1)).get(7L);
    }

    @Test
    void wrongEpochAndWrongWsAuditReturnUnauthorized() {
        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token(NORMAL, 2, null));
        when(authStateClient.get(7L)).thenReturn(ok(state(
                7L, "ACTIVE", 10L, null, "NORMAL", true)));
        MockHttpServletResponse epochResponse = new MockHttpServletResponse();

        boolean epochAllowed = interceptor.beforeHandshake(
                request(), serverResponse(epochResponse), mock(WebSocketHandler.class), new HashMap<>());

        assertThat(epochAllowed).isFalse();
        assertThat(epochResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(metrics).jwtRejected(PassengerAuthMetrics.JwtRejectReason.EPOCH_MISMATCH);
        clearInvocations(metrics);

        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token(NORMAL, 1, null));
        when(authStateClient.get(7L)).thenReturn(ok(state(
                7L, "ACTIVE", 9L, null, "NORMAL", true)));
        MockHttpServletResponse auditResponse = new MockHttpServletResponse();

        boolean auditAllowed = interceptor.beforeHandshake(
                request(), serverResponse(auditResponse), mock(WebSocketHandler.class), new HashMap<>());

        assertThat(auditAllowed).isFalse();
        assertThat(auditResponse.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(metrics).jwtRejected(PassengerAuthMetrics.JwtRejectReason.STATE_MISMATCH);
        verify(authStateClient, times(2)).get(7L);
    }

    @Test
    void invalidSignatureReturnsUnauthorizedWithoutAuthorityLookup() {
        when(jwtService.parseAndVerify("jwt-value")).thenThrow(new io.jsonwebtoken.JwtException("bad signature"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.beforeHandshake(
                request(), serverResponse(response), mock(WebSocketHandler.class), new HashMap<>());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(authStateClient, never()).get(7L);
    }

    @Test
    void passengerConnectionFailureReturnsServiceUnavailable() {
        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token(NORMAL, 2, null));
        when(authStateClient.get(7L)).thenThrow(retryable());
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.beforeHandshake(
                request(), serverResponse(response), mock(WebSocketHandler.class), new HashMap<>());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        verify(authStateClient, times(1)).get(7L);
    }

    @Test
    void passengerFiveHundredReturnsServiceUnavailable() {
        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token(NORMAL, 2, null));
        when(authStateClient.get(7L)).thenThrow(serverError());
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.beforeHandshake(
                request(), serverResponse(response), mock(WebSocketHandler.class), new HashMap<>());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        verify(authStateClient).get(7L);
    }

    private static ServletServerHttpRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/ws/v1/stream");
        request.setRequestURI("/app/ws/v1/stream");
        request.setQueryString("token=jwt-value");
        return new ServletServerHttpRequest(request);
    }

    private static ServletServerHttpResponse serverResponse(MockHttpServletResponse response) {
        return new ServletServerHttpResponse(response);
    }

    private static ParsedPassengerJwt token(com.sx.passengerapi.auth.PassengerSessionScope scope,
                                            int audit,
                                            String operationNo) {
        return new ParsedPassengerJwt(7L, "", 9L, scope, audit, operationNo);
    }

    private static InternalAuthStateResponse state(long customerId,
                                                   String lifecycleStatus,
                                                   long epoch,
                                                   String operationNo,
                                                   String scope,
                                                   boolean allowed) {
        return new InternalAuthStateResponse(
                customerId, 1, lifecycleStatus, epoch, operationNo, scope, allowed);
    }

    private static ResponseVo<InternalAuthStateResponse> ok(InternalAuthStateResponse state) {
        return ResponseVo.success(state);
    }

    private static RetryableException retryable() {
        Request request = Request.create(
                Request.HttpMethod.GET,
                "http://passenger/api/v1/internal/auth-state/7",
                Map.of(),
                new byte[0],
                StandardCharsets.UTF_8);
        return new RetryableException(-1, "passenger unavailable", Request.HttpMethod.GET,
                new ConnectException("connection refused"), (Long) null, request);
    }

    private static FeignException serverError() {
        Request request = Request.create(
                Request.HttpMethod.GET,
                "http://passenger/api/v1/internal/auth-state/7",
                Map.of(),
                new byte[0],
                StandardCharsets.UTF_8);
        Response response = Response.builder()
                .request(request)
                .status(500)
                .reason("Internal Server Error")
                .headers(Map.of())
                .build();
        return FeignException.errorStatus("PassengerCoreAuthStateClient#get", response);
    }
}
