package com.sx.adminapi.auth;

import com.sx.adminapi.config.AdminJwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtService {

    private final AdminJwtProperties props;

    public JwtService(AdminJwtProperties props) {
        this.props = props;
    }

    public String createToken(long userId, long tokenVersion, String username) {
        long now = System.currentTimeMillis();
        long expMs = now + props.getExpirationSeconds() * 1000L;
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("tv", tokenVersion)
                .claim("uname", username)
                .audience().add(props.getAudience()).and()
                .issuedAt(new Date(now))
                .expiration(new Date(expMs))
                .signWith(signingKey())
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationSeconds() {
        return props.getExpirationSeconds();
    }

    private SecretKey signingKey() {
        String secret = props.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("admin-api admin.jwt.secret is empty");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
