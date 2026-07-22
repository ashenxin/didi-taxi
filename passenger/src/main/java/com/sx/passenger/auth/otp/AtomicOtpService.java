package com.sx.passenger.auth.otp;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Service
public class AtomicOtpService {

    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then return 0 end
            if current ~= ARGV[1] then return 1 end
            redis.call('DEL', KEYS[1])
            return 2
            """, Long.class);

    private final StringRedisTemplate redis;
    private final OtpKeyFactory keys;

    public AtomicOtpService(StringRedisTemplate redis, OtpKeyFactory keys) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.keys = Objects.requireNonNull(keys, "keys must not be null");
    }

    public void store(OtpPurpose purpose, OtpSubject subject, String code, Duration ttl) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("OTP code must not be blank");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("OTP ttl must be positive");
        }
        redis.opsForValue().set(keys.key(purpose, subject), code, ttl);
    }

    public OtpConsumeResult consume(OtpPurpose purpose, OtpSubject subject, String submittedCode) {
        if (submittedCode == null || submittedCode.isBlank()) {
            return OtpConsumeResult.MISMATCH;
        }
        Long result = redis.execute(CONSUME, List.of(keys.key(purpose, subject)), submittedCode.trim());
        if (result == null) {
            throw new IllegalStateException("OTP store unavailable");
        }
        return switch (result.intValue()) {
            case 0 -> OtpConsumeResult.MISSING;
            case 1 -> OtpConsumeResult.MISMATCH;
            case 2 -> OtpConsumeResult.CONSUMED;
            default -> throw new IllegalStateException("Unknown OTP consume result: " + result);
        };
    }
}
