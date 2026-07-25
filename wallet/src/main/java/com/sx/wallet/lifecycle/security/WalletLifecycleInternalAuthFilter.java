package com.sx.wallet.lifecycle.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.wallet.common.util.ResultUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@EnableConfigurationProperties(WalletLifecycleInternalAuthProperties.class)
public class WalletLifecycleInternalAuthFilter extends OncePerRequestFilter {
    static final String ROOT = "/api/v1/internal/account-lifecycle/wallet";
    private final WalletLifecycleInternalAuthProperties properties;
    private final ObjectMapper objectMapper;

    public WalletLifecycleInternalAuthFilter(WalletLifecycleInternalAuthProperties properties,
                                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        String path = context != null && !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length()) : uri;
        return !protectedPath(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String supplied = request.getHeader("X-Internal-Token");
        String configured = properties.getToken();
        int status = supplied == null ? 401 : 403;
        if (supplied == null || configured == null || configured.isBlank()
                || !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(status);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(),
                    ResultUtil.error(status, "INTERNAL_AUTH_REJECTED", "内部身份校验失败", null));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean protectedPath(String raw) {
        String candidate = raw;
        for (int i = 0; i <= raw.length(); i++) {
            if (candidate.equals(ROOT) || candidate.startsWith(ROOT + "/")
                    || candidate.startsWith(ROOT + ";")
                    || candidate.startsWith(ROOT + "%")) return true;
            try {
                String decoded = UriUtils.decode(candidate, StandardCharsets.UTF_8);
                if (decoded.equals(candidate)) break;
                candidate = decoded;
            } catch (IllegalArgumentException ex) {
                return candidate.startsWith(ROOT);
            }
        }
        return false;
    }
}
