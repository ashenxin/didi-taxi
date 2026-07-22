package com.sx.passenger.internal.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.passenger.common.util.ResultUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@EnableConfigurationProperties(PassengerInternalAuthProperties.class)
@Slf4j
public class PassengerInternalAuthFilter extends OncePerRequestFilter {

    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String APP_PREFIX = "/api/v1/app";
    private static final String INTERNAL_PREFIX = "/api/v1/internal";

    private final PassengerInternalAuthProperties properties;
    private final ObjectMapper objectMapper;

    public PassengerInternalAuthFilter(PassengerInternalAuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        ServletRequestPathUtils.parseAndCache(request);
        String path = ServletRequestPathUtils.getCachedPathValue(request);
        return !matchesPrefix(path, APP_PREFIX) && !matchesPrefix(path, INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String supplied = request.getHeader(INTERNAL_TOKEN_HEADER);
        if (supplied == null) {
            reject(request, response, HttpServletResponse.SC_UNAUTHORIZED, "Missing internal service identity");
            return;
        }
        if (!matches(supplied)) {
            reject(request, response, HttpServletResponse.SC_FORBIDDEN, "Invalid internal service identity");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean matches(String supplied) {
        String configured = properties.getToken();
        if (configured == null || configured.isBlank()) {
            return false;
        }
        byte[] expected = configured.getBytes(StandardCharsets.UTF_8);
        byte[] actual = supplied.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, int status, String message)
            throws IOException {
        log.warn("passenger internal authentication rejected uri={} requestId={} status={}",
                request.getRequestURI(), request.getHeader(REQUEST_ID_HEADER), status);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), status == HttpServletResponse.SC_UNAUTHORIZED
                ? ResultUtil.unauthorized(message)
                : ResultUtil.forbidden(message));
    }

    private static boolean matchesPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
