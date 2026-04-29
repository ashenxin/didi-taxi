package com.sx.order.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 司机-乘客匹配屏蔽：用于「司机拒绝/取消后，短时间内不再匹配同一乘客」。
 */
@Service
@Slf4j
public class DriverPassengerMatchBlockService {

    private static final String KEY_PREFIX = "tx:dispatch:block:dp:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public DriverPassengerMatchBlockService(
            StringRedisTemplate redisTemplate,
            @Value("${order.dispatch.driver-passenger-block-minutes:30}") long blockMinutes) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofMinutes(Math.max(1, blockMinutes));
    }

    public void block(Long driverId, Long passengerId) {
        if (driverId == null || passengerId == null) {
            return;
        }
        String key = buildKey(driverId, passengerId);
        try {
            redisTemplate.opsForValue().set(key, "1", ttl);
        } catch (Exception e) {
            log.warn("写入司机乘客屏蔽键失败 driverId={} passengerId={}: {}", driverId, passengerId, e.toString());
        }
    }

    public boolean isBlocked(Long driverId, Long passengerId) {
        if (driverId == null || passengerId == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(driverId, passengerId)));
        } catch (Exception e) {
            log.warn("读取司机乘客屏蔽键失败 driverId={} passengerId={}: {}", driverId, passengerId, e.toString());
            return false;
        }
    }

    private static String buildKey(Long driverId, Long passengerId) {
        return KEY_PREFIX + driverId + ":" + passengerId;
    }
}
