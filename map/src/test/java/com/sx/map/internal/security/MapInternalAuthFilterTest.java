package com.sx.map.internal.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class MapInternalAuthFilterTest {

    private MapInternalAuthFilter filter;

    @BeforeEach
    void setUp() {
        MapInternalAuthProperties properties = new MapInternalAuthProperties();
        properties.setToken("test-map-internal-token");
        filter = new MapInternalAuthFilter(properties, new ObjectMapper());
    }

    @Test
    void rejectedInternalRequestReturnsStableAiErrorCode() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/ai/route/extract");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("鉴权失败时不应进入内部接口");
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("X-Ai-Error-Code")).isEqualTo("AI_INTERNAL_AUTH_FAILED");
    }

    @Test
    void validInternalTokenContinuesFilterChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/ai/route/extract");
        request.addHeader("X-Internal-Service-Token", "test-map-internal-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> continued.set(true));

        assertThat(continued).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
