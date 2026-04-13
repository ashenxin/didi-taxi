package com.sx.gateway.filter;

import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * 去掉 BFF 返回的 CORS 响应头，避免与 {@code spring.cloud.gateway.globalcors} 叠成
 * {@code Access-Control-Allow-Origin: http://localhost:5173, *}（浏览器拒收）。
 * 设计上 CORS 仅由网关下发；下游若仍因旧部署 / 误配带了 {@code *}，在此剥离。
 */
@Component
@Order(0)
public class StripDownstreamCorsHttpHeadersFilter implements HttpHeadersFilter {

    private static final List<String> STRIP = List.of(
            HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
            HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
            HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
            HttpHeaders.ACCESS_CONTROL_MAX_AGE,
            HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
            HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS
    );

    @Override
    public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
        HttpHeaders out = new HttpHeaders();
        out.addAll(input);
        for (String h : STRIP) {
            out.remove(h);
        }
        return out;
    }

    @Override
    public boolean supports(Type type) {
        return type == Type.RESPONSE;
    }
}
