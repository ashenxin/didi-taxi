package com.sx.driverapi.ws;

import com.sx.driverapi.auth.DriverJwtService;
import com.sx.driverapi.auth.DriverTokenVersionStore;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

@Component
@Slf4j
public class DriverWsHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_DRIVER_ID = "driverId";

    private final DriverJwtService jwtService;
    private final DriverTokenVersionStore tokenVersionStore;

    public DriverWsHandshakeInterceptor(DriverJwtService jwtService, DriverTokenVersionStore tokenVersionStore) {
        this.jwtService = jwtService;
        this.tokenVersionStore = tokenVersionStore;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        try {
            String token = extractToken(request);
            var parsed = jwtService.parseAndVerify(token);
            if (parsed.audit() != 2) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            Long tv = tokenVersionStore.currentVersion(parsed.driverId());
            if (tv == null || tv.longValue() != parsed.tokenVersion()) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            attributes.put(ATTR_DRIVER_ID, parsed.driverId());
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        } catch (Exception e) {
            log.warn("WS handshake error: {}", e.toString());
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private static String extractToken(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest sreq) {
            String header = sreq.getServletRequest().getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                return header.substring("Bearer ".length()).trim();
            }
        }
        URI uri = request.getURI();
        String q = uri == null ? null : uri.getQuery();
        if (q == null || q.isBlank()) {
            throw new IllegalArgumentException("missing token");
        }
        for (String part : q.split("&")) {
            int idx = part.indexOf('=');
            if (idx <= 0) continue;
            String k = part.substring(0, idx);
            String v = part.substring(idx + 1);
            if ("token".equals(k) && v != null && !v.isBlank()) {
                return java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("missing token");
    }
}

