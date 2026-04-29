package com.sx.passengerapi.auth;

import com.sx.passengerapi.config.AppJwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class AppJwtService {

    private final AppJwtProperties props;

    public AppJwtService(AppJwtProperties props) {
        this.props = props;
    }

    /**
     * 签发乘客访问令牌；{@code sub} 为 customerId，含 {@code aud}、{@code tv}（与 Redis 登出版本对齐）。
     */
    public String createPassengerToken(long customerId, String phone, long tokenVersion) {
        long now = System.currentTimeMillis();
        long expMs = now + props.getExpirationSeconds() * 1000L;
        return Jwts.builder()
                .subject(String.valueOf(customerId))
                .claim("phone", phone == null ? "" : phone)
                .claim("tv", tokenVersion)
                .audience().add(props.getAudience()).and()
                .issuedAt(new Date(now))
                .expiration(new Date(expMs))
                .signWith(signingKey())
                .compact();
    }

    public ParsedPassengerJwt parseAndVerify(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtException("missing token");
        }
        Claims c = Jwts.parser()
                .verifyWith(signingKey())
                .requireAudience(props.getAudience())
                .build()
                .parseSignedClaims(token.trim())
                .getPayload();
        long customerId = Long.parseLong(c.getSubject());
        Object tvObj = c.get("tv");
        if (tvObj == null) {
            throw new JwtException("missing tv");
        }
        long tv = tvObj instanceof Number ? ((Number) tvObj).longValue() : Long.parseLong(tvObj.toString());
        String phone = c.get("phone", String.class);
        return new ParsedPassengerJwt(customerId, phone, tv);
    }

    public long getExpirationSeconds() {
        return props.getExpirationSeconds();
    }

    private SecretKey signingKey() {
        String secret = props.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("passenger-api app.jwt.secret is empty");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
