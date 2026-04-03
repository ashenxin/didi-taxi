package com.sx.adminapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.adminapi.auth.JwtService;
import com.sx.adminapi.client.PassengerAdminSysClient;
import com.sx.adminapi.client.dto.PassengerSecurityContextData;
import com.sx.adminapi.common.vo.ResponseVo;
import feign.FeignException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final PassengerAdminSysClient passengerAdminSysClient;
    private final ObjectMapper objectMapper;
    private final AdminSecurityContextRedisCache securityContextRedisCache;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            PassengerAdminSysClient passengerAdminSysClient,
            ObjectMapper objectMapper,
            AdminSecurityContextRedisCache securityContextRedisCache) {
        this.jwtService = jwtService;
        this.passengerAdminSysClient = passengerAdminSysClient;
        this.objectMapper = objectMapper;
        this.securityContextRedisCache = securityContextRedisCache;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getServletPath();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (path.startsWith("/actuator/")) {
            return true;
        }
        return "POST".equalsIgnoreCase(request.getMethod()) && "/admin/api/v1/auth/login".equals(path);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            JsonAuthResponseWriter.unauthorized(response, objectMapper, "未登录或登录已失效");
            return;
        }
        String token = header.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            JsonAuthResponseWriter.unauthorized(response, objectMapper, "未登录或登录已失效");
            return;
        }

        final Claims claims;
        try {
            claims = jwtService.parse(token);
        } catch (JwtException | IllegalArgumentException ex) {
            JsonAuthResponseWriter.unauthorized(response, objectMapper, "未登录或登录已失效");
            return;
        }

        long userId;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException ex) {
            JsonAuthResponseWriter.unauthorized(response, objectMapper, "未登录或登录已失效");
            return;
        }

        Number tvNum = claims.get("tv", Number.class);
        if (tvNum == null) {
            JsonAuthResponseWriter.unauthorized(response, objectMapper, "未登录或登录已失效");
            return;
        }
        long tokenVersion = tvNum.longValue();

        PassengerSecurityContextData ctx = securityContextRedisCache.get(userId, tokenVersion).orElse(null);

        if (ctx == null) {
            final ResponseVo<PassengerSecurityContextData> vo;
            try {
                vo = passengerAdminSysClient.securityContext(userId);
            } catch (FeignException.NotFound e) {
                JsonAuthResponseWriter.unauthorized(response, objectMapper, "未登录或登录已失效");
                return;
            } catch (FeignException.Forbidden e) {
                JsonAuthResponseWriter.forbidden(response, objectMapper, "账号数据不一致，请联系管理员");
                return;
            } catch (FeignException e) {
                int st = e.status();
                if (st >= 500 || st == -1) {
                    JsonAuthResponseWriter.badGateway(response, objectMapper);
                    return;
                }
                JsonAuthResponseWriter.unauthorized(response, objectMapper, "未登录或登录已失效");
                return;
            }

            if (vo.getCode() == null || vo.getCode() != 200 || vo.getData() == null) {
                JsonAuthResponseWriter.unauthorized(response, objectMapper, "未登录或登录已失效");
                return;
            }
            ctx = vo.getData();
            if (!Objects.equals(ctx.getTokenVersion(), tokenVersion)
                    || ctx.getStatus() == null
                    || ctx.getStatus() != 1) {
                JsonAuthResponseWriter.unauthorized(response, objectMapper, "未登录或登录已失效");
                return;
            }
            securityContextRedisCache.put(userId, ctx);
        }

        List<SimpleGrantedAuthority> authorities = ctx.getRoleCodes() == null
                ? List.of()
                : ctx.getRoleCodes().stream()
                        .map(c -> new SimpleGrantedAuthority("ROLE_" + c))
                        .collect(Collectors.toList());

        AdminLoginUser principal = new AdminLoginUser(
                ctx.getUserId(),
                ctx.getTokenVersion(),
                ctx.getUsername(),
                ctx.getDisplayName(),
                ctx.getRoleCodes() == null ? List.of() : List.copyOf(ctx.getRoleCodes()),
                ctx.getProvinceCode(),
                ctx.getCityCode(),
                ctx.getStatus());

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }
}
