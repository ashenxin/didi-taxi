package com.sx.gateway.jwt;

import com.sx.gateway.config.GatewayJwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Component
public class GatewayJwtVerifier {

    private final GatewayJwtProperties props;

    public GatewayJwtVerifier(GatewayJwtProperties props) {
        this.props = props;
    }

    /**
     * @return subject (user id)，验签与 exp 通过；aud 按路径校验（若开启）
     * @throws ExpiredJwtException   exp 过期
     * @throws JwtException          其它 JWT 错误
     */
    public String verifyAndGetSubject(String compactToken, String path) throws JwtException {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey(path))
                .build()
                .parseSignedClaims(compactToken)
                .getPayload();
        if (props.isAudienceCheckEnabled()) {
            String expected = expectedAudience(path);
            if (expected != null && !audienceMatches(claims, expected)) {
                throw new MalformedJwtException("JWT audience does not match route");
            }
        }
        return claims.getSubject();
    }

    private SecretKey signingKey(String path) {
        String secret = resolveSecretByPath(path);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("gateway jwt secret is empty for path=" + path);
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private String resolveSecretByPath(String path) {
        if (path != null) {
            if (path.startsWith("/admin")) {
                String s = props.getSecretAdmin();
                if (s != null && !s.isBlank()) {
                    return s;
                }
            }
            if (path.startsWith("/app")) {
                String s = props.getSecretApp();
                if (s != null && !s.isBlank()) {
                    return s;
                }
            }
            if (path.startsWith("/driver")) {
                String s = props.getSecretDriver();
                if (s != null && !s.isBlank()) {
                    return s;
                }
            }
        }
        return props.getSecret();
    }

    private String expectedAudience(String path) {
        if (path.startsWith("/admin")) {
            return props.getAudienceAdmin();
        }
        if (path.startsWith("/app")) {
            return props.getAudienceApp();
        }
        if (path.startsWith("/driver")) {
            return props.getAudienceDriver();
        }
        return null;
    }

    private boolean audienceMatches(Claims claims, String expected) {
        Collection<String> audiences = extractAudiences(claims);
        return audiences.stream().anyMatch(expected::equals);
    }

    @SuppressWarnings("unchecked")
    private Collection<String> extractAudiences(Claims claims) {
        Object aud = claims.get("aud");
        if (aud == null) {
            return Collections.emptyList();
        }
        if (aud instanceof String s) {
            return List.of(s);
        }
        if (aud instanceof Collection<?> c) {
            return (Collection<String>) c;
        }
        return Collections.emptyList();
    }
}
