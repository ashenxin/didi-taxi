package com.sx.gateway.filter;

import com.sx.gateway.config.PassengerWsPrecheckProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/** 在 Gateway/Netty 返回 101 之前向 Passenger API 询问 WS 小票是否仍有效。 */
@Component
public class PassengerWsPrecheckGlobalFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(PassengerWsPrecheckGlobalFilter.class);
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";
    private static final String PRECHECK_PATH = "/app/internal/v1/ws/precheck";

    private final WebClient webClient;
    private final PassengerWsPrecheckProperties properties;

    public PassengerWsPrecheckGlobalFilter(WebClient.Builder builder,
                                           PassengerWsPrecheckProperties properties) {
        this.webClient = builder.build();
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!properties.isEnabled() || !HttpMethod.GET.equals(exchange.getRequest().getMethod())
                || !path.startsWith("/app/ws/")) {
            return chain.filter(exchange);
        }
        String ticket = exchange.getRequest().getQueryParams().getFirst("token");
        if (ticket == null || ticket.isBlank()) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            log.error("Passenger WS precheck internal token is not configured");
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE);
        }
        return webClient.post()
                .uri(properties.getServiceBaseUrl() + PRECHECK_PATH)
                .header(INTERNAL_TOKEN_HEADER, properties.getInternalToken())
                .bodyValue(Map.of("ticket", ticket))
                .exchangeToMono(response -> Mono.just(response.statusCode()))
                .timeout(Duration.ofMillis(Math.max(100, properties.getTimeoutMillis())))
                .onErrorResume(error -> {
                    log.warn("Passenger WS precheck unavailable type={}", error.getClass().getSimpleName());
                    return Mono.just(HttpStatus.SERVICE_UNAVAILABLE);
                })
                .flatMap(statusCode -> {
                    if (statusCode.is2xxSuccessful()) {
                        return chain.filter(exchange);
                    }
                    HttpStatus status = HttpStatus.resolve(statusCode.value());
                    if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN
                            || status == HttpStatus.SERVICE_UNAVAILABLE) {
                        return reject(exchange, status);
                    }
                    return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE);
                });
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 30;
    }
}
