package com.sx.calculate.lifecycle.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CalculateLifecycleInternalAuthFilterTest {
    private static final String TOKEN = "test-calculate-lifecycle-internal-secret";
    private CalculateLifecycleInternalAuthFilter filter;

    @BeforeEach
    void setUp() {
        CalculateLifecycleInternalAuthProperties properties =
                new CalculateLifecycleInternalAuthProperties();
        properties.setToken(TOKEN);
        filter = new CalculateLifecycleInternalAuthFilter(properties, new ObjectMapper());
    }

    @Test
    void protectedRootAndDescendantsRejectMissingOrInvalidToken() throws Exception {
        assertRejected("/api/v1/internal/account-lifecycle/calculate", null, 401);
        assertRejected("/api/v1/internal/account-lifecycle/calculate/fence", "wrong", 403);

        AtomicBoolean invoked = new AtomicBoolean();
        MockHttpServletResponse response = execute(
                "/api/v1/internal/account-lifecycle/calculate/actions", "", TOKEN, invoked);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(invoked).isTrue();
    }

    @Test
    void encodedAndContextPathVariantsCannotBypassProtection() throws Exception {
        assertRejected("/calculate/api/v1/internal/account-lifecycle;probe/calculate/fence",
                "/calculate", null, 401);
        assertRejected("/api/v1/internal/account-lifecycle%2Fcalculate/fence", "", null, 401);
        assertRejected("/api/v1/internal/account-lifecycle%252Fcalculate/fence", "", null, 401);
    }

    @Test
    void unrelatedPathsAreNotIntercepted() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        MockHttpServletResponse response = execute(
                "/api/v1/internal/account-lifecycles/calculate/fence", "", null, invoked);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(invoked).isTrue();
    }

    private void assertRejected(String path, String token, int status) throws Exception {
        assertRejected(path, "", token, status);
    }

    private void assertRejected(String path, String context, String token, int status) throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        MockHttpServletResponse response = execute(path, context, token, invoked);
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(invoked).isFalse();
    }

    private MockHttpServletResponse execute(String path, String context, String token,
                                            AtomicBoolean invoked) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContextPath(context);
        request.setRequestURI(path);
        if (token != null) request.addHeader("X-Internal-Token", token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (chainRequest, chainResponse) -> invoked.set(true);
        filter.doFilter(request, response, chain);
        return response;
    }
}
