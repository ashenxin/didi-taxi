package com.sx.order.lifecycle.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class OrderLifecycleInternalAuthFilterTest {
    private static final String HEADER = "X-Internal-Token";
    private static final String TOKEN = "test-order-lifecycle-internal-secret";

    private OrderLifecycleInternalAuthFilter filter;

    @BeforeEach
    void setUp() {
        OrderLifecycleInternalAuthProperties properties = new OrderLifecycleInternalAuthProperties();
        properties.setToken(TOKEN);
        filter = new OrderLifecycleInternalAuthFilter(properties, new ObjectMapper());
    }

    @Test
    void exactRootAndDescendantsRequireTokenWithStableHttpStatus() throws Exception {
        assertRejected("/api/v1/internal/account-lifecycle/order", null, 401);
        assertRejected("/api/v1/internal/account-lifecycle/order/fence", "wrong", 403);

        AtomicBoolean invoked = new AtomicBoolean();
        MockHttpServletResponse response = execute(
                "/api/v1/internal/account-lifecycle/order/fence", "", TOKEN, invoked);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(invoked).isTrue();
    }

    @Test
    void matrixContextEncodedAndDoubleEncodedVariantsCannotBypassProtection() throws Exception {
        assertRejected("/order/api/v1/internal/account-lifecycle;probe/order/fence", "/order", null, 401);
        assertRejected("/api/v1/internal/account-lifecycle%2Forder/fence", "", null, 401);
        assertRejected("/api/v1/internal/account-lifecycle%252Forder/fence", "", null, 401);
        assertRejected("/api/v1/internal/account-lifecycle%25252Forder/fence", "", null, 401);
    }

    @Test
    void similarButUnrelatedPathsAreNotIntercepted() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        MockHttpServletResponse response = execute(
                "/api/v1/internal/account-lifecycles/order/fence", "", null, invoked);
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
        if (token != null) request.addHeader(HEADER, token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (chainRequest, chainResponse) -> invoked.set(true);
        filter.doFilter(request, response, chain);
        return response;
    }
}
