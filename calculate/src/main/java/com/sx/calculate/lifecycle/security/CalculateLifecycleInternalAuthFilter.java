package com.sx.calculate.lifecycle.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.calculate.common.util.ResultUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.UriUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@EnableConfigurationProperties(CalculateLifecycleInternalAuthProperties.class)
@Slf4j
public class CalculateLifecycleInternalAuthFilter extends OncePerRequestFilter {
    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String ROOT_TEXT = "/api/v1/internal/account-lifecycle/calculate";
    private static final PathPattern ROOT = PathPatternParser.defaultInstance.parse(ROOT_TEXT);
    private static final PathPattern DESCENDANTS =
            PathPatternParser.defaultInstance.parse(ROOT_TEXT + "/**");

    private final CalculateLifecycleInternalAuthProperties properties;
    private final ObjectMapper objectMapper;

    public CalculateLifecycleInternalAuthFilter(
            CalculateLifecycleInternalAuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        try {
            if (isProtectedPath(ServletRequestPathUtils.parseAndCache(request)
                    .pathWithinApplication().value())) {
                return false;
            }
        } catch (RuntimeException ignored) {
            // 原始 URI 仍参与失败关闭判断，避免畸形编码绕过内部边界。
        }
        return !isProtectedPath(pathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader(INTERNAL_TOKEN_HEADER);
        if (supplied == null) {
            reject(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Missing internal identity");
            return;
        }
        if (!tokenMatches(supplied)) {
            reject(request, response, HttpServletResponse.SC_FORBIDDEN,
                    "Invalid internal identity");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean tokenMatches(String supplied) {
        String configured = properties.getToken();
        if (configured == null || configured.isBlank()) return false;
        return MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletRequest request, HttpServletResponse response,
                        int status, String message) throws IOException {
        log.warn("calculate lifecycle internal authentication rejected uri={} requestId={} status={}",
                request.getRequestURI(), request.getHeader(REQUEST_ID_HEADER), status);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ResultUtil.error(status, message));
    }

    private static boolean isProtectedPath(String rawPath) {
        String candidate = rawPath;
        for (int decodeCount = 0; decodeCount <= rawPath.length(); decodeCount++) {
            if (pathMatches(candidate) || hasProtectedPrefixBoundary(candidate)) return true;
            try {
                String decoded = UriUtils.decode(candidate, StandardCharsets.UTF_8);
                if (decoded.equals(candidate)) break;
                candidate = decoded;
            } catch (IllegalArgumentException ex) {
                return hasProtectedPrefixBoundary(candidate);
            }
        }
        return false;
    }

    private static boolean pathMatches(String path) {
        try {
            PathContainer container = PathContainer.parsePath(path);
            return ROOT.matches(container) || DESCENDANTS.matches(container);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean hasProtectedPrefixBoundary(String path) {
        if (!path.startsWith(ROOT_TEXT)) return false;
        if (path.length() == ROOT_TEXT.length()) return true;
        char boundary = path.charAt(ROOT_TEXT.length());
        return boundary == '/' || boundary == ';' || boundary == '%';
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length()) : uri;
    }
}
