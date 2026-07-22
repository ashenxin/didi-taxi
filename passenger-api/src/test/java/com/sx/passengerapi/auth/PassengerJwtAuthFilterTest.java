package com.sx.passengerapi.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passengerapi.client.PassengerCoreAuthStateClient;
import com.sx.passengerapi.client.dto.InternalAuthStateResponse;
import com.sx.passengerapi.common.vo.ResponseVo;
import feign.Request;
import feign.RetryableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.sx.passengerapi.auth.PassengerSessionScope.LIFECYCLE_RESTRICTED;
import static com.sx.passengerapi.auth.PassengerSessionScope.NORMAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PassengerJwtAuthFilterTest {

    private final AppJwtService jwtService = mock(AppJwtService.class);
    private final PassengerCoreAuthStateClient authStateClient = mock(PassengerCoreAuthStateClient.class);
    private final PassengerAuthDecisionService decisionService = new PassengerAuthDecisionService();
    private PassengerJwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PassengerJwtAuthFilter(jwtService, authStateClient, decisionService, new ObjectMapper());
    }

    @Test
    void activeNormalSessionAllowsExactlyOneStateLookupAndInjectsTrustedHeaders() throws Exception {
        ParsedPassengerJwt token = token(NORMAL, 1, null);
        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token);
        when(authStateClient.get(7L)).thenReturn(ok(state(7L, "ACTIVE", 9L, null, "NORMAL", true)));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = execute("GET", "/app/api/v1/orders/current", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        ArgumentCaptor<ServletRequest> request = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(request.capture(), any());
        PassengerAuthRequestWrapper wrapped = (PassengerAuthRequestWrapper) request.getValue();
        assertThat(wrapped.getHeader("X-User-Id")).isEqualTo("7");
        assertThat(wrapped.getHeader("X-User-Phone")).isEqualTo("13800138000");
        assertThat(wrapped.getHeader("X-Auth-Epoch")).isEqualTo("9");
        assertThat(wrapped.getHeader("X-Auth-Scope")).isEqualTo("NORMAL");
        assertThat(wrapped.getHeader("X-Lifecycle-Operation-No")).isNull();
        verify(authStateClient, times(1)).get(7L);
    }

    @Test
    void matchingRestrictedSessionIsAuthoritativeButOrdinaryBusinessPathIsForbidden() throws Exception {
        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token(LIFECYCLE_RESTRICTED, 1, "op-1"));
        when(authStateClient.get(7L)).thenReturn(ok(
                state(7L, "CANCELLING", 9L, "op-1", "LIFECYCLE_RESTRICTED", true)));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = execute("POST", "/app/api/v1/orders", chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
        verify(authStateClient, times(1)).get(7L);
    }

    @Test
    void customerEpochScopeAndOperationMismatchEachReturnUnauthorized() throws Exception {
        assertUnauthorized(token(NORMAL, 1, null), state(8L, "ACTIVE", 9L, null, "NORMAL", true));
        assertUnauthorized(token(NORMAL, 1, null), state(7L, "ACTIVE", 10L, null, "NORMAL", true));
        assertUnauthorized(token(NORMAL, 1, null),
                state(7L, "CANCELLING", 9L, null, "LIFECYCLE_RESTRICTED", true));
        assertUnauthorized(token(LIFECYCLE_RESTRICTED, 1, "op-1"),
                state(7L, "CANCELLING", 9L, "op-2", "LIFECYCLE_RESTRICTED", true));
    }

    @Test
    void disallowedStateAndWrongHttpAuditReturnUnauthorized() throws Exception {
        assertUnauthorized(token(NORMAL, 1, null), state(7L, "CANCELLED", 9L, null, null, false));
        assertUnauthorized(token(NORMAL, 1, null),
                state(7L, "CANCELLING", 9L, null, "NORMAL", true));
        assertUnauthorized(token(NORMAL, 2, null), state(7L, "ACTIVE", 9L, null, "NORMAL", true));
    }

    @Test
    void passengerFiveHundredAndConnectionFailureReturnServiceUnavailable() throws Exception {
        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token(NORMAL, 1, null));
        doThrow(retryable(500, new IllegalStateException("downstream 500")))
                .when(authStateClient).get(7L);
        FilterChain firstChain = mock(FilterChain.class);

        MockHttpServletResponse first = execute("GET", "/app/api/v1/orders/current", firstChain);

        assertThat(first.getStatus()).isEqualTo(503);
        verify(firstChain, never()).doFilter(any(), any());

        doThrow(retryable(-1, new ConnectException("connection refused")))
                .when(authStateClient).get(7L);
        FilterChain secondChain = mock(FilterChain.class);
        MockHttpServletResponse second = execute("GET", "/app/api/v1/orders/current", secondChain);

        assertThat(second.getStatus()).isEqualTo(503);
        verify(secondChain, never()).doFilter(any(), any());
    }

    @Test
    void publicLoginSkipsJwtAndAuthorityLookup() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = execute("POST", "/app/api/v1/auth/login-sms", chain, false);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
        verify(jwtService, never()).parseAndVerify(any());
        verify(authStateClient, never()).get(any(Long.class));
    }

    private void assertUnauthorized(ParsedPassengerJwt token, InternalAuthStateResponse state) throws Exception {
        clearInvocations(authStateClient);
        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token);
        when(authStateClient.get(7L)).thenReturn(ok(state));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = execute("GET", "/app/api/v1/orders/current", chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
        verify(authStateClient, times(1)).get(7L);
    }

    private MockHttpServletResponse execute(String method, String path, FilterChain chain) throws Exception {
        return execute(method, path, chain, true);
    }

    private MockHttpServletResponse execute(String method, String path, FilterChain chain, boolean authorized)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        if (authorized) {
            request.addHeader("Authorization", "Bearer jwt-value");
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static ParsedPassengerJwt token(PassengerSessionScope scope, int audit, String operationNo) {
        return new ParsedPassengerJwt(7L, "13800138000", 9L, scope, audit, operationNo);
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

    private static RetryableException retryable(int status, Exception cause) {
        Request request = Request.create(
                Request.HttpMethod.GET,
                "http://passenger/api/v1/internal/auth-state/7",
                Map.of(),
                new byte[0],
                StandardCharsets.UTF_8);
        return new RetryableException(status, "passenger unavailable", Request.HttpMethod.GET,
                cause, (Long) null, request);
    }
}
