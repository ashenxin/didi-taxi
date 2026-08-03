package com.sx.gateway.filter;

import com.sx.gateway.config.PassengerWsPrecheckProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class PassengerWsPrecheckGlobalFilterTest {

    @Test
    void validTicketIsPrecheckedBeforeForwardingUpgrade() {
        AtomicBoolean called = new AtomicBoolean();
        PassengerWsPrecheckGlobalFilter filter = filter(HttpStatus.OK, called);
        MockServerWebExchange exchange = exchange("ticket-value");
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(called).isTrue();
        assertThat(forwarded).isTrue();
    }

    @Test
    void staleTicketReturns401WithoutForwardingUpgrade() {
        AtomicBoolean called = new AtomicBoolean();
        PassengerWsPrecheckGlobalFilter filter = filter(HttpStatus.UNAUTHORIZED, called);
        MockServerWebExchange exchange = exchange("stale-ticket");
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(called).isTrue();
        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void missingTicketIsRejectedWithoutCallingPassengerApi() {
        AtomicBoolean called = new AtomicBoolean();
        PassengerWsPrecheckGlobalFilter filter = filter(HttpStatus.OK, called);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/app/ws/v1/stream"));

        filter.filter(exchange, ignored -> Mono.error(new AssertionError("must not forward"))).block();

        assertThat(called).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static PassengerWsPrecheckGlobalFilter filter(HttpStatus status, AtomicBoolean called) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            called.set(true);
            assertThat(request.headers().getFirst("X-Internal-Service-Token"))
                    .isEqualTo("internal-token-value");
            return Mono.just(ClientResponse.create(status).build());
        });
        PassengerWsPrecheckProperties properties = new PassengerWsPrecheckProperties();
        properties.setInternalToken("internal-token-value");
        properties.setServiceBaseUrl("http://passenger-api");
        return new PassengerWsPrecheckGlobalFilter(builder, properties);
    }

    private static MockServerWebExchange exchange(String ticket) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(
                "/app/ws/v1/stream?token=" + ticket));
    }
}
