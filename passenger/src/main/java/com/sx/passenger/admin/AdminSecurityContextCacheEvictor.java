package com.sx.passenger.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 与 {@code admin-api} 共用键前缀，在后台用户信息变更后删除 JWT 校验用的 security-context 缓存，避免旧数据用到下一次 {@code token_version} 递增。
 */
@Slf4j
@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class AdminSecurityContextCacheEvictor {

    private final StringRedisTemplate redis;
    private final String keyPrefix;

    public AdminSecurityContextCacheEvictor(
            StringRedisTemplate redis,
            @Value("${passenger.admin.security-context-cache.key-prefix:adm:user:}") String keyPrefix) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
    }

    public void evict(long userId) {
        try {
            redis.delete(keyPrefix + userId);
        } catch (RuntimeException e) {
            log.warn("Evict admin security-context cache failed userId={}: {}", userId, e.toString());
        }
    }
}
