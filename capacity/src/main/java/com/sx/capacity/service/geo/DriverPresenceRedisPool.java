package com.sx.capacity.service.geo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 司机级 Presence：按城市用 ZSET 保存每个司机最后心跳时间，避免对整个城市 GEO key 使用 TTL。
 */
@Component
@Slf4j
public class DriverPresenceRedisPool {

    private static final String KEY_PREFIX = "tx:driver:presence:";
    private static final String CITIES_KEY = "tx:driver:presence:cities";
    private static final DefaultRedisScript<Long> REMOVE_IF_EXPIRED_SCRIPT = new DefaultRedisScript<>("""
            local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if not score or tonumber(score) > tonumber(ARGV[2]) then
                return 0
            end
            return redis.call('ZREM', KEYS[1], ARGV[1])
            """, Long.class);

    private final StringRedisTemplate redis;
    private final long heartbeatTimeoutMs;

    public DriverPresenceRedisPool(StringRedisTemplate redis,
                                   @Value("${capacity.dispatch.driver-heartbeat-timeout-seconds:60}") long heartbeatTimeoutSeconds) {
        this.redis = redis;
        this.heartbeatTimeoutMs = Math.max(15, heartbeatTimeoutSeconds) * 1000L;
    }

    public void touch(String cityCode, Long driverId) {
        if (!valid(cityCode, driverId)) return;
        try {
            redis.opsForZSet().add(key(cityCode), String.valueOf(driverId), System.currentTimeMillis());
            redis.opsForSet().add(CITIES_KEY, cityCode);
        } catch (Exception e) {
            log.warn("司机 Presence 更新失败 cityCode={} driverId={}: {}", cityCode, driverId, e.toString());
        }
    }

    public void remove(String cityCode, Long driverId) {
        if (!valid(cityCode, driverId)) return;
        try {
            redis.opsForZSet().remove(key(cityCode), String.valueOf(driverId));
        } catch (Exception e) {
            log.warn("司机 Presence 移除失败 cityCode={} driverId={}: {}", cityCode, driverId, e.toString());
        }
    }

    public boolean isFresh(String cityCode, Long driverId) {
        if (!valid(cityCode, driverId)) return false;
        try {
            Double score = redis.opsForZSet().score(key(cityCode), String.valueOf(driverId));
            return score != null && score.longValue() >= cutoffMs();
        } catch (Exception e) {
            log.warn("司机 Presence 查询失败 cityCode={} driverId={}: {}", cityCode, driverId, e.toString());
            return false;
        }
    }

    public List<ExpiredPresence> listExpired(int limitPerCity) {
        int limit = Math.max(1, limitPerCity);
        List<ExpiredPresence> out = new ArrayList<>();
        try {
            Set<String> cities = redis.opsForSet().members(CITIES_KEY);
            if (cities == null) return out;
            long cutoff = cutoffMs();
            for (String cityCode : cities) {
                Set<String> ids = redis.opsForZSet().rangeByScore(key(cityCode), 0, cutoff, 0, limit);
                if (ids == null) continue;
                for (String id : ids) {
                    try {
                        out.add(new ExpiredPresence(cityCode, Long.valueOf(id), cutoff));
                    } catch (NumberFormatException ignored) {
                        redis.opsForZSet().remove(key(cityCode), id);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("司机 Presence 过期扫描失败: {}", e.toString());
        }
        return out;
    }

    public boolean removeIfExpired(ExpiredPresence expired) {
        if (expired == null || !valid(expired.cityCode(), expired.driverId())) return false;
        try {
            String member = String.valueOf(expired.driverId());
            Long removed = redis.execute(
                    REMOVE_IF_EXPIRED_SCRIPT,
                    Collections.singletonList(key(expired.cityCode())),
                    member,
                    String.valueOf(expired.cutoffMs()));
            return removed != null && removed > 0;
        } catch (Exception e) {
            log.warn("司机 Presence 过期移除失败 cityCode={} driverId={}: {}",
                    expired.cityCode(), expired.driverId(), e.toString());
            return false;
        }
    }

    private long cutoffMs() {
        return System.currentTimeMillis() - heartbeatTimeoutMs;
    }

    private static boolean valid(String cityCode, Long driverId) {
        return cityCode != null && !cityCode.isBlank() && driverId != null;
    }

    private static String key(String cityCode) {
        return KEY_PREFIX + cityCode;
    }

    public record ExpiredPresence(String cityCode, Long driverId, long cutoffMs) {
    }
}
