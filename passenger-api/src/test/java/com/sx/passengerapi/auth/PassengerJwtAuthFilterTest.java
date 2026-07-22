package com.sx.passengerapi.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passengerapi.client.PassengerCoreAuthStateClient;
import com.sx.passengerapi.client.dto.InternalAuthStateResponse;
import com.sx.passengerapi.auth.action.PassengerActionPolicy;
import com.sx.passengerapi.auth.action.PassengerActionResolver;
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
    private final PassengerAuthMetrics metrics = mock(PassengerAuthMetrics.class);
    private final PassengerAuthDecisionService decisionService = new PassengerAuthDecisionService();
    private PassengerJwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PassengerJwtAuthFilter(jwtService, authStateClient, decisionService, new ObjectMapper(), metrics,
                new PassengerActionResolver(), new PassengerActionPolicy());
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
    void matchingRestrictedSessionCanReadCancelAndPayOutstandingOrders() throws Exception {
        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token(LIFECYCLE_RESTRICTED, 1, "op-1"));
        when(authStateClient.get(7L)).thenReturn(ok(
                state(7L, "CANCELLING", 9L, "op-1", "LIFECYCLE_RESTRICTED", true)));

        for (String[] route : new String[][]{
                {"GET", "/app/api/v1/orders"},
                {"GET", "/app/api/v1/orders/O-1"},
                {"POST", "/app/api/v1/orders/O-1/cancel"},
                {"POST", "/app/api/v1/orders/O-1/payments"}}) {
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletResponse response = execute(route[0], route[1], chain);
            assertThat(response.getStatus()).isEqualTo(200);
            verify(chain).doFilter(any(PassengerAuthRequestWrapper.class), any());
        }
        verify(authStateClient, times(4)).get(7L);
    }

    @Test
    void activeSessionFailsClosedWhenProtectedRouteHasNoActionMapping() throws Exception {
        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token(NORMAL, 1, null));
        when(authStateClient.get(7L)).thenReturn(ok(state(7L, "ACTIVE", 9L, null, "NORMAL", true)));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = execute("GET", "/app/api/v1/not-mapped", chain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(chain, never()).doFilter(any(), any());
        verify(authStateClient).get(7L);
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

    @Test
    void publicLoginPatternIsExactAndDoesNotExposeDescendants() throws Exception {
        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token(NORMAL, 1, null));
        when(authStateClient.get(7L)).thenReturn(ok(state(7L, "ACTIVE", 9L, null, "NORMAL", true)));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = execute(
                request("POST", "/app/api/v1/auth/login-sms/extra", "", true), chain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(authStateClient).get(7L);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void contextPathCannotBypassProtectedAuthenticationOrInjectTrustedHeaders() throws Exception {
        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token(NORMAL, 1, null));
        when(authStateClient.get(7L)).thenReturn(ok(state(7L, "ACTIVE", 9L, null, "NORMAL", true)));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = request("GET", "/taxi/app/api/v1/orders/current", "/taxi", true);
        request.addHeader(PassengerAuthRequestWrapper.USER_ID, "999");

        MockHttpServletResponse response = execute(request, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        ArgumentCaptor<ServletRequest> captured = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(captured.capture(), any());
        assertThat(captured.getValue()).isInstanceOf(PassengerAuthRequestWrapper.class);
        assertThat(((PassengerAuthRequestWrapper) captured.getValue())
                .getHeader(PassengerAuthRequestWrapper.USER_ID)).isEqualTo("7");
        verify(authStateClient).get(7L);
    }

    @Test
    void matrixPathsUseNormalizedExactPublicAndProtectedPatterns() throws Exception {
        FilterChain publicChain = mock(FilterChain.class);

        MockHttpServletResponse publicResponse = execute(
                request("POST", "/app/api/v1/auth/login-sms;channel=app", "", false), publicChain);

        assertThat(publicResponse.getStatus()).isEqualTo(200);
        verify(publicChain).doFilter(any(), any());
        verify(authStateClient, never()).get(any(Long.class));

        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token(NORMAL, 1, null));
        when(authStateClient.get(7L)).thenReturn(ok(state(7L, "ACTIVE", 9L, null, "NORMAL", true)));
        FilterChain protectedChain = mock(FilterChain.class);

        MockHttpServletResponse protectedResponse = execute(
                request("GET", "/app/api/v1/orders;view=current", "", true), protectedChain);

        assertThat(protectedResponse.getStatus()).isEqualTo(200);
        verify(authStateClient).get(7L);
    }

    @Test
    void encodedAndDoubleEncodedAppPathsFailClosedThroughDatabaseAuthentication() throws Exception {
        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token(NORMAL, 1, null));
        when(authStateClient.get(7L)).thenReturn(ok(state(7L, "ACTIVE", 9L, null, "NORMAL", true)));

        FilterChain encodedChain = mock(FilterChain.class);
        MockHttpServletResponse encoded = execute(
                request("GET", "/app%2Fapi%2Fv1/orders/current", "", true), encodedChain);
        FilterChain doubleEncodedChain = mock(FilterChain.class);
        MockHttpServletResponse doubleEncoded = execute(
                request("GET", "/app%252Fapi%252Fv1/orders/current", "", true), doubleEncodedChain);

        assertThat(encoded.getStatus()).isEqualTo(503);
        assertThat(doubleEncoded.getStatus()).isEqualTo(503);
        verify(authStateClient, times(2)).get(7L);
        verify(encodedChain, never()).doFilter(any(), any());
        verify(doubleEncodedChain, never()).doFilter(any(), any());
    }

    private void assertUnauthorized(ParsedPassengerJwt token, InternalAuthStateResponse state) throws Exception {
        clearInvocations(authStateClient, metrics);
        when(jwtService.parseAndVerify("jwt-value")).thenReturn(token);
        when(authStateClient.get(7L)).thenReturn(ok(state));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = execute("GET", "/app/api/v1/orders/current", chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
        verify(authStateClient, times(1)).get(7L);
        verify(metrics).jwtRejected(token.authEpoch() != state.getAuthEpoch()
                ? PassengerAuthMetrics.JwtRejectReason.EPOCH_MISMATCH
                : PassengerAuthMetrics.JwtRejectReason.STATE_MISMATCH);
    }

    private MockHttpServletResponse execute(String method, String path, FilterChain chain) throws Exception {
        return execute(method, path, chain, true);
    }

    private MockHttpServletResponse execute(String method, String path, FilterChain chain, boolean authorized)
            throws Exception {
        MockHttpServletRequest request = request(method, path, "", authorized);
        return execute(request, chain);
    }

    private MockHttpServletResponse execute(MockHttpServletRequest request, FilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest request(
            String method, String requestUri, String contextPath, boolean authorized) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, requestUri);
        request.setContextPath(contextPath);
        request.setRequestURI(requestUri);
        if (authorized) {
            request.addHeader("Authorization", "Bearer jwt-value");
        }
        return request;
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
                customerId, 0, lifecycleStatus, epoch, operationNo, scope, allowed);
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
