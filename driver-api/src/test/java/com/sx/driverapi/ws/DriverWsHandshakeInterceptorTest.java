package com.sx.driverapi.ws;

import com.sx.driverapi.auth.DriverJwtService;
import com.sx.driverapi.auth.DriverTokenVersionStore;
import com.sx.driverapi.auth.ParsedDriverJwt;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

import java.net.URI;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriverWsHandshakeInterceptorTest {

    private DriverJwtService jwtService;
    private DriverTokenVersionStore tokenVersionStore;
    private DriverWsHandshakeInterceptor interceptor;
    private ServerHttpResponse response;
    private HashMap<String, Object> attributes;

    @BeforeEach
    void setUp() {
        jwtService = mock(DriverJwtService.class);
        tokenVersionStore = mock(DriverTokenVersionStore.class);
        interceptor = new DriverWsHandshakeInterceptor(jwtService, tokenVersionStore);
        response = mock(ServerHttpResponse.class);
        attributes = new HashMap<>();
    }

    @Test
    void acceptsValidWsTokenAndStoresDriverId() {
        when(jwtService.parseAndVerify("ws-token")).thenReturn(new ParsedDriverJwt(80001L, "13800138000", 3L, 2));
        when(tokenVersionStore.currentVersion(80001L)).thenReturn(3L);

        boolean ok = interceptor.beforeHandshake(request("ws-token"), response, null, attributes);

        assertThat(ok).isTrue();
        assertThat(attributes.get(DriverWsHandshakeInterceptor.ATTR_DRIVER_ID)).isEqualTo(80001L);
    }

    @Test
    void rejectsHttpAuditToken() {
        when(jwtService.parseAndVerify("api-token")).thenReturn(new ParsedDriverJwt(80001L, "13800138000", 3L, 1));

        boolean ok = interceptor.beforeHandshake(request("api-token"), response, null, attributes);

        assertThat(ok).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsTokenVersionMismatch() {
        when(jwtService.parseAndVerify("old-token")).thenReturn(new ParsedDriverJwt(80001L, "13800138000", 2L, 2));
        when(tokenVersionStore.currentVersion(80001L)).thenReturn(3L);

        boolean ok = interceptor.beforeHandshake(request("old-token"), response, null, attributes);

        assertThat(ok).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsMissingOrInvalidToken() {
        when(jwtService.parseAndVerify("bad-token")).thenThrow(new JwtException("bad token"));

        boolean ok = interceptor.beforeHandshake(request("bad-token"), response, null, attributes);

        assertThat(ok).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    private static ServerHttpRequest request(String token) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://127.0.0.1:8101/driver/ws/v1/stream?token=" + token));
        return request;
    }
}
