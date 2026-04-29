package com.sx.capacity.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 司机-乘客匹配屏蔽读取（键由 order-service 写入）。
 */
@Service
@Slf4j
public class DriverPassengerMatchBlockService {

    private static final String KEY_PREFIX = "tx:dispatch:block:dp:";

    private final StringRedisTemplate redisTemplate;

    public DriverPassengerMatchBlockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isBlocked(Long driverId, Long passengerId) {
        if (driverId == null || passengerId == null) {
            return false;
        }
        String key = KEY_PREFIX + driverId + ":" + passengerId;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("读取司机乘客屏蔽键失败 driverId={} passengerId={}: {}", driverId, passengerId, e.toString());
            return false;
        }
    }
}
