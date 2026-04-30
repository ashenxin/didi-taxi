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
     * 签发乘客 Token；{@code sub} 为 customerId。
     *
     * @param audit {@code 1}=HTTP API；{@code 2}=WebSocket 握手（参见司机端）。
     */
    public String createPassengerToken(long customerId, String phone, long tokenVersion, int audit) {
        long now = System.currentTimeMillis();
        long expMs = now + props.getExpirationSeconds() * 1000L;
        return Jwts.builder()
                .subject(String.valueOf(customerId))
                .claim("phone", phone == null ? "" : phone)
                .claim("tv", tokenVersion)
                .claim("audit", audit)
                .audience().add(props.getAudience()).and()
                .issuedAt(new Date(now))
                .expiration(new Date(expMs))
                .signWith(signingKey())
                .compact();
    }

    /**
     * 解析 JWT。若 JWT 缺少 {@code audit} claim，按 {@code 1} 解析以兼容存量 Token。
     */
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
        Object auditObj = c.get("audit");
        int audit = auditObj == null ? 1
                : (auditObj instanceof Number ? ((Number) auditObj).intValue() : Integer.parseInt(auditObj.toString()));
        String phone = c.get("phone", String.class);
        return new ParsedPassengerJwt(customerId, phone, tv, audit);
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
