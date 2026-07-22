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

import static com.sx.passengerapi.auth.PassengerSessionScope.LIFECYCLE_RESTRICTED;
import static com.sx.passengerapi.auth.PassengerSessionScope.NORMAL;

@Component
public class AppJwtService {

    private final AppJwtProperties props;

    public AppJwtService(AppJwtProperties props) {
        this.props = props;
    }

    /**
     * 签发乘客 Token；{@code sub} 为 customerId。
     *
     * @param audit {@code 1}=HTTP API；{@code 2}=WebSocket 握手。
     */
    public String createPassengerToken(long customerId,
                                       String phone,
                                       long authEpoch,
                                       PassengerSessionScope scope,
                                       int audit,
                                       String operationNo) {
        requireValidCombination(authEpoch, scope, audit, operationNo);
        long now = System.currentTimeMillis();
        long ttl = scope == LIFECYCLE_RESTRICTED
                ? props.getRestrictedExpirationSeconds()
                : props.getExpirationSeconds();
        long expMs = now + ttl * 1000L;
        return Jwts.builder()
                .subject(String.valueOf(customerId))
                .claim("phone", phone == null ? "" : phone)
                .claim("ae", authEpoch)
                .claim("scope", scope.name())
                .claim("audit", audit)
                .claim("operationNo", operationNo)
                .audience().add(props.getAudience()).and()
                .issuedAt(new Date(now))
                .expiration(new Date(expMs))
                .signWith(signingKey())
                .compact();
    }

    /**
     * Task 5 会把现有登录签发调用切到显式 scope；过渡期间该入口也只签发严格 ae/scope claims。
     */
    public String createPassengerToken(long customerId, String phone, long authEpoch, int audit) {
        return createPassengerToken(customerId, phone, authEpoch, NORMAL, audit, null);
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
        try {
            long customerId = Long.parseLong(c.getSubject());
            long authEpoch = strictLong(c.get("ae"), "ae");
            Object scopeValue = c.get("scope");
            if (!(scopeValue instanceof String scopeName)) {
                throw new JwtException("invalid scope");
            }
            PassengerSessionScope scope;
            try {
                scope = PassengerSessionScope.valueOf(scopeName);
            } catch (IllegalArgumentException e) {
                throw new JwtException("invalid scope", e);
            }
            int audit = strictInt(c.get("audit"), "audit");
            Object operationValue = c.get("operationNo");
            if (operationValue != null && !(operationValue instanceof String)) {
                throw new JwtException("invalid operationNo");
            }
            String operationNo = (String) operationValue;
            requireValidCombination(authEpoch, scope, audit, operationNo);
            String phone = c.get("phone", String.class);
            return new ParsedPassengerJwt(customerId, phone, authEpoch, scope, audit, operationNo);
        } catch (JwtException e) {
            throw e;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new JwtException("invalid passenger claims", e);
        }
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

    private static long strictLong(Object value, String claim) {
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) {
            throw new JwtException("invalid " + claim);
        }
        return ((Number) value).longValue();
    }

    private static int strictInt(Object value, String claim) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer)) {
            throw new JwtException("invalid " + claim);
        }
        return ((Number) value).intValue();
    }

    private static void requireValidCombination(long authEpoch,
                                                PassengerSessionScope scope,
                                                int audit,
                                                String operationNo) {
        if (authEpoch < 1 || scope == null) {
            throw new IllegalArgumentException("invalid passenger session");
        }
        if (scope == NORMAL) {
            if ((audit != 1 && audit != 2) || operationNo != null) {
                throw new IllegalArgumentException("invalid normal passenger session");
            }
            return;
        }
        if (audit != 1 || operationNo == null || operationNo.isBlank()) {
            throw new IllegalArgumentException("invalid restricted passenger session");
        }
    }
}
