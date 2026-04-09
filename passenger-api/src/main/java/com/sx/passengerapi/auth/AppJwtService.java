package com.sx.passengerapi.auth;

import com.sx.passengerapi.config.AppJwtProperties;
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
     * 签发乘客访问令牌；{@code sub} 为 customerId，含 {@code aud} 供网关校验。
     */
    public String createPassengerToken(long customerId, String phone) {
        long now = System.currentTimeMillis();
        long expMs = now + props.getExpirationSeconds() * 1000L;
        return Jwts.builder()
                .subject(String.valueOf(customerId))
                .claim("phone", phone)
                .audience().add(props.getAudience()).and()
                .issuedAt(new Date(now))
                .expiration(new Date(expMs))
                .signWith(signingKey())
                .compact();
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
