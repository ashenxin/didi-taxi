package com.sx.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripDownstreamCorsHttpHeadersFilterTest {

    @Test
    void stripsDownstreamCorsHeadersButPreservesBusinessHeaders() {
        HttpHeaders input = new HttpHeaders();
        input.add(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        input.add(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        input.add(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "X-Request-Id");
        input.add(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
        input.add(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST");
        input.add(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization");
        input.add("X-Business-Header", "kept");

        HttpHeaders output = new StripDownstreamCorsHttpHeadersFilter().filter(input, null);

        assertFalse(output.containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertFalse(output.containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertFalse(output.containsKey(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS));
        assertFalse(output.containsKey(HttpHeaders.ACCESS_CONTROL_MAX_AGE));
        assertFalse(output.containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
        assertFalse(output.containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS));
        assertEquals("kept", output.getFirst("X-Business-Header"));
        assertEquals("*", input.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void appliesOnlyToResponseHeaders() {
        StripDownstreamCorsHttpHeadersFilter filter = new StripDownstreamCorsHttpHeadersFilter();

        assertTrue(filter.supports(HttpHeadersFilter.Type.RESPONSE));
        assertFalse(filter.supports(HttpHeadersFilter.Type.REQUEST));
    }
}
