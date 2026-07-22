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
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@EnableConfigurationProperties(PassengerInternalAuthProperties.class)
@Slf4j
public class PassengerInternalAuthFilter extends OncePerRequestFilter {

    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final PathPattern APP_ROOT = pathPattern("/api/v1/app");
    private static final PathPattern APP_DESCENDANTS = pathPattern("/api/v1/app/**");
    private static final PathPattern INTERNAL_ROOT = pathPattern("/api/v1/internal");
    private static final PathPattern INTERNAL_DESCENDANTS = pathPattern("/api/v1/internal/**");

    private final PassengerInternalAuthProperties properties;
    private final ObjectMapper objectMapper;

    public PassengerInternalAuthFilter(PassengerInternalAuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = ServletRequestPathUtils.parseAndCache(request).pathWithinApplication();
        return !APP_ROOT.matches(path)
                && !APP_DESCENDANTS.matches(path)
                && !INTERNAL_ROOT.matches(path)
                && !INTERNAL_DESCENDANTS.matches(path);
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

    private static PathPattern pathPattern(String pattern) {
        return PathPatternParser.defaultInstance.parse(pattern);
    }
}
