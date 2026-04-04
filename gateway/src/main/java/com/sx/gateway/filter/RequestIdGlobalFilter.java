package com.sx.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 透传或生成 {@code X-Request-Id}，便于全链路排查。
 */
@Component
public class RequestIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String rid = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (rid == null || rid.isBlank()) {
            rid = UUID.randomUUID().toString();
        }
        String finalRid = rid;
        ServerHttpRequest request = exchange.getRequest().mutate().header(HEADER, finalRid).build();
        ServerWebExchange rewritten = exchange.mutate().request(request).build();
        rewritten.getResponse().getHeaders().add(HEADER, finalRid);
        return chain.filter(rewritten);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
