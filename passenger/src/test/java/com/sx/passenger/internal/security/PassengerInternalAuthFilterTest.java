package com.sx.passenger.internal.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class PassengerInternalAuthFilterTest {

    private static final String HEADER = "X-Internal-Service-Token";
    private static final String TOKEN = "test-passenger-internal-secret-32bytes";

    private PassengerInternalAuthFilter filter;

    @BeforeEach
    void setUp() {
        PassengerInternalAuthProperties properties = new PassengerInternalAuthProperties();
        properties.setToken(TOKEN);
        filter = new PassengerInternalAuthFilter(properties, new ObjectMapper());
    }

    @Test
    void rejectsMissingInternalTokenWithUnauthorizedResponse() throws Exception {
        MockHttpServletResponse response = execute("/api/v1/internal/auth-state/7", null, new AtomicBoolean());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("\"code\":401");
    }

    @Test
    void rejectsWrongInternalTokenWithForbiddenResponse() throws Exception {
        MockHttpServletResponse response = execute("/api/v1/internal/auth-state/7", "wrong", new AtomicBoolean());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":403");
    }

    @Test
    void acceptsExactInternalToken() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();

        MockHttpServletResponse response = execute("/api/v1/internal/auth-state/7", TOKEN, invoked);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(invoked).isTrue();
    }

    @Test
    void protectsExistingAppEntrypoints() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();

        MockHttpServletResponse response = execute("/api/v1/app/auth/login-password", null, invoked);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(invoked).isFalse();
    }

    @Test
    void ignoresPathsOutsideAppAndInternalPrefixes() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();

        MockHttpServletResponse response = execute("/api/v1/customers/7", null, invoked);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(invoked).isTrue();
    }

    private MockHttpServletResponse execute(String path, String token, AtomicBoolean invoked) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (token != null) {
            request.addHeader(HEADER, token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (chainRequest, chainResponse) -> invoked.set(true);
        filter.doFilter(request, response, chain);
        return response;
    }
}
