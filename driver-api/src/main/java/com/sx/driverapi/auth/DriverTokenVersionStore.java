package com.sx.driverapi.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 司机 token 版本：登录/登出时递增，JWT {@code tv} 须与当前值一致。
 * <p>无 Redis（如本地 test 排除自动配置）时退化为进程内 Map。</p>
 */
@Component
public class DriverTokenVersionStore {

    private static final String KEY_PREFIX = "driver:tv:";

    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<Long, Long> memory = new ConcurrentHashMap<>();

    public DriverTokenVersionStore(@Autowired(required = false) StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(long driverId) {
        return KEY_PREFIX + driverId;
    }

    /**
     * 递增并返回新版本（登录、登出均用此语义使旧 JWT 失效）。
     */
    public long nextVersion(long driverId) {
        if (redis != null) {
            Long v = redis.opsForValue().increment(key(driverId));
            return v != null ? v : 1L;
        }
        return memory.merge(driverId, 1L, Long::sum);
    }

    public Long currentVersion(long driverId) {
        if (redis != null) {
            String s = redis.opsForValue().get(key(driverId));
            return s == null ? null : Long.parseLong(s);
        }
        return memory.get(driverId);
    }
}
