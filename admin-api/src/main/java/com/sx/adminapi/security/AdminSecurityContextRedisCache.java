package com.sx.adminapi.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.adminapi.client.dto.PassengerSecurityContextData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 缓存 {@code passenger} 的 security-context，减少每次请求 Feign。
 * 键与 passenger 侧删除逻辑共用前缀 {@code admin.security-context-cache.key-prefix}。
 * 缓存项须与 JWT 内 {@code tv} 一致才可能命中；否则回源 Feign。Redis 不可用时静默降级为仅 Feign。
 */
@Slf4j
@Component
public class AdminSecurityContextRedisCache {

    private final boolean enabled;
    private final String keyPrefix;
    private final long ttlSeconds;
    private final ObjectMapper objectMapper;
    private final Optional<StringRedisTemplate> redis;

    public AdminSecurityContextRedisCache(
            @Value("${admin.security-context-cache.enabled:true}") boolean enabled,
            @Value("${admin.security-context-cache.key-prefix:adm:user:}") String keyPrefix,
            @Value("${admin.security-context-cache.ttl-seconds:1800}") long ttlSeconds,
            ObjectMapper objectMapper,
            @Autowired(required = false) StringRedisTemplate stringRedisTemplate) {
        this.enabled = enabled;
        this.keyPrefix = keyPrefix;
        this.ttlSeconds = Math.max(60L, ttlSeconds);
        this.objectMapper = objectMapper;
        this.redis = Optional.ofNullable(stringRedisTemplate);
    }

    /**
     * 读取缓存；仅当与入参 {@code jwtTokenVersion} 一致且用户启用时才返回。
     */
    public Optional<PassengerSecurityContextData> get(long userId, long jwtTokenVersion) {
        if (!enabled || redis.isEmpty()) {
            return Optional.empty();
        }
        String raw;
        try {
            raw = redis.get().opsForValue().get(cacheKey(userId));
        } catch (RuntimeException e) {
            log.warn("Redis get admin security-context failed userId={}: {}", userId, e.toString());
            return Optional.empty();
        }
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            PassengerSecurityContextData ctx = objectMapper.readValue(raw, PassengerSecurityContextData.class);
            if (ctx.getTokenVersion() == null || !ctx.getTokenVersion().equals(jwtTokenVersion)) {
                return Optional.empty();
            }
            if (ctx.getStatus() == null || ctx.getStatus() != 1) {
                return Optional.empty();
            }
            return Optional.of(ctx);
        } catch (JsonProcessingException e) {
            log.warn("Redis admin security-context JSON corrupt userId={}", userId);
            try {
                redis.get().delete(cacheKey(userId));
            } catch (RuntimeException ignored) {
                // ignore
            }
            return Optional.empty();
        }
    }

    /**
     * 写入或刷新缓存（Feign 回源成功后调用）。
     */
    public void put(long userId, PassengerSecurityContextData ctx) {
        if (!enabled || redis.isEmpty() || ctx == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(ctx);
            redis.get().opsForValue().set(cacheKey(userId), json, ttlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.warn("Serialize admin security-context for Redis failed userId={}", userId);
        } catch (RuntimeException e) {
            log.warn("Redis set admin security-context failed userId={}: {}", userId, e.toString());
        }
    }

    private String cacheKey(long userId) {
        return keyPrefix + userId;
    }
}
