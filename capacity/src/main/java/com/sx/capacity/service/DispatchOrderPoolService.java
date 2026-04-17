package com.sx.capacity.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 订单池：指派成功后的待接单索引（driverId → orderNo 集合），辅助对账；权威仍以订单库为准。
 */
@Service
@Slf4j
public class DispatchOrderPoolService {

    private static final String KEY_PREFIX = "tx:order:pending:";

    private final StringRedisTemplate redis;

    public DispatchOrderPoolService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void addPending(Long driverId, String orderNo) {
        if (driverId == null || orderNo == null || orderNo.isBlank()) {
            return;
        }
        String key = KEY_PREFIX + driverId;
        try {
            redis.opsForSet().add(key, orderNo);
            redis.expire(key, Duration.ofDays(1));
        } catch (Exception e) {
            log.warn("订单池写入失败 driverId={} orderNo={}: {}", driverId, orderNo, e.toString());
        }
    }

    public void removePending(Long driverId, String orderNo) {
        if (driverId == null || orderNo == null || orderNo.isBlank()) {
            return;
        }
        String key = KEY_PREFIX + driverId;
        try {
            redis.opsForSet().remove(key, orderNo);
        } catch (Exception e) {
            log.warn("订单池移除失败 driverId={} orderNo={}: {}", driverId, orderNo, e.toString());
        }
    }
}
