package com.sx.passengerapi.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 乘客 token 版本：登录/登出时递增，JWT {@code tv} 须与 Redis 当前值一致（与司机端 {@code driver:tv:*} 同思路）。
 */
@Component
public class PassengerTokenVersionStore {

    private static final String KEY_PREFIX = "passenger:tv:";

    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<Long, Long> memory = new ConcurrentHashMap<>();

    public PassengerTokenVersionStore(@Autowired(required = false) StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(long customerId) {
        return KEY_PREFIX + customerId;
    }

    /** 递增并返回新版本（登录、登出均用此语义使旧 JWT 失效）。 */
    public long nextVersion(long customerId) {
        if (redis != null) {
            Long v = redis.opsForValue().increment(key(customerId));
            return v != null ? v : 1L;
        }
        return memory.merge(customerId, 1L, Long::sum);
    }

    public Long currentVersion(long customerId) {
        if (redis != null) {
            String s = redis.opsForValue().get(key(customerId));
            return s == null ? null : Long.parseLong(s);
        }
        return memory.get(customerId);
    }
}
