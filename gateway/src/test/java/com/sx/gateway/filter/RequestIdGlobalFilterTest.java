package com.sx.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RequestIdGlobalFilterTest {

    @Test
    void preservesExistingRequestIdOnRequestAndResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/app/api/v1/orders")
                .header(RequestIdGlobalFilter.HEADER, "request-123"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        new RequestIdGlobalFilter().filter(exchange, captured -> {
            forwarded.set(captured);
            return Mono.empty();
        }).block();

        assertEquals("request-123", forwarded.get().getRequest().getHeaders()
                .getFirst(RequestIdGlobalFilter.HEADER));
        assertEquals("request-123", exchange.getResponse().getHeaders()
                .getFirst(RequestIdGlobalFilter.HEADER));
    }

    @Test
    void generatesUuidRequestIdWhenHeaderIsMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/driver/api/v1/orders/assigned"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        new RequestIdGlobalFilter().filter(exchange, captured -> {
            forwarded.set(captured);
            return Mono.empty();
        }).block();

        String generated = forwarded.get().getRequest().getHeaders().getFirst(RequestIdGlobalFilter.HEADER);
        assertNotNull(generated);
        assertDoesNotThrow(() -> UUID.fromString(generated));
        assertEquals(generated, exchange.getResponse().getHeaders().getFirst(RequestIdGlobalFilter.HEADER));
    }
}
