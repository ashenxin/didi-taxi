package com.sx.driverapi.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class DriverJwtService {

    private final DriverJwtProperties props;

    public DriverJwtService(DriverJwtProperties props) {
        this.props = props;
    }

    /**
     * @param audit 1=HTTP API；2=WebSocket 握手（与文档对齐）
     */
    public String createDriverToken(long driverId, String phone, long tokenVersion, int audit) {
        long now = System.currentTimeMillis();
        long expMs = now + props.getExpirationSeconds() * 1000L;
        return Jwts.builder()
                .subject(String.valueOf(driverId))
                .claim("phone", phone == null ? "" : phone)
                .claim("tv", tokenVersion)
                .claim("audit", audit)
                .audience().add(props.getAudience()).and()
                .issuedAt(new Date(now))
                .expiration(new Date(expMs))
                .signWith(signingKey())
                .compact();
    }

    public ParsedDriverJwt parseAndVerify(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtException("missing token");
        }
        Claims c = Jwts.parser()
                .verifyWith(signingKey())
                .requireAudience(props.getAudience())
                .build()
                .parseSignedClaims(token.trim())
                .getPayload();
        long driverId = Long.parseLong(c.getSubject());
        Object tvObj = c.get("tv");
        if (tvObj == null) {
            throw new JwtException("missing tv");
        }
        long tv = tvObj instanceof Number ? ((Number) tvObj).longValue() : Long.parseLong(tvObj.toString());
        Object audObj = c.get("audit");
        if (audObj == null) {
            throw new JwtException("missing audit");
        }
        int audit = audObj instanceof Number ? ((Number) audObj).intValue() : Integer.parseInt(audObj.toString());
        String phone = c.get("phone", String.class);
        return new ParsedDriverJwt(driverId, phone, tv, audit);
    }

    public long getExpirationSeconds() {
        return props.getExpirationSeconds();
    }

    private SecretKey signingKey() {
        String secret = props.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("driver-api app.jwt.secret is empty");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
