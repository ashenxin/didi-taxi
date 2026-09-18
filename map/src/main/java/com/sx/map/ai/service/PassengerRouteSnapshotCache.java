package com.sx.map.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.ai.model.PassengerRouteSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * map-service 内部的完整客服路线快照缓存。
 *
 * 路线引用由服务端生成随机 UUID。缓存键只用于定位数据，不授予读取权限；读取方还必须
 * 提供已从可信会话上下文取得的乘客、会话和条件版本；起终点从当前已确认条件读取。缓存不可用或快照
 * 失效会返回空结果，由上层在原对话中要求乘客重新确认地点，不能从聊天摘要恢复旧坐标。
 * 本类不写客服消息，也不将完整路线交给模型或客户端。
 */
@Service
public class PassengerRouteSnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(PassengerRouteSnapshotCache.class);
    private static final String KEY_PREFIX = "map:ai:route:snapshot:v1:";
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(5);
    private static final String AMAP_PROVIDER = "gaode";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PassengerRouteConditionsCache conditionsCache;

    public PassengerRouteSnapshotCache(StringRedisTemplate redis,
                                       ObjectMapper objectMapper,
                                       PassengerRouteConditionsCache conditionsCache) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.conditionsCache = conditionsCache;
    }

    /**
     * 生成不可预测的快照引用。创建路线的服务应在构造快照前调用此方法。
     */
    public String newRouteRef() {
        return UUID.randomUUID().toString();
    }

    /**
     * 缓存已选定、可展示的真实高德驾车路线。
     *
     * 写入失败必须中止卡片交付；不能仅保存展示摘要后让乘客得到无法继续核查的 routeRef。
     * Redis TTL 和快照自身失效时间都不超过生成时间后的五分钟，读取不会延长有效期。
     */
    public void save(PassengerRouteSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("路线快照不能为空");
        }
        if (!isRandomRouteRef(snapshot.routeRef())) {
            throw new IllegalArgumentException("路线引用必须使用服务端生成的随机UUID");
        }
        if (!AMAP_PROVIDER.equals(snapshot.provider())) {
            throw new IllegalArgumentException("只能缓存真实高德路线");
        }
        PassengerRouteConditionsCache.ConfirmedConditions current = conditionsCache
                .find(snapshot.customerId(), snapshot.conversationNo(), snapshot.conditionVersion())
                .orElseThrow(() -> new IllegalStateException("路线快照缺少有效的已确认起终点"));
        if (!current.origin().equals(snapshot.origin())
                || !current.destination().equals(snapshot.destination())) {
            throw new IllegalStateException("路线快照与当前已确认起终点不符");
        }

        Instant now = Instant.now();
        if (snapshot.generatedAt().isAfter(now)) {
            throw new IllegalArgumentException("路线生成时间不能晚于当前时间");
        }
        Duration age = Duration.between(snapshot.generatedAt(), now);
        Duration remaining = Duration.between(now, snapshot.expiresAt());
        Duration declaredLifetime = Duration.between(snapshot.generatedAt(), snapshot.expiresAt());
        if (declaredLifetime.compareTo(MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException("路线快照有效期不能超过五分钟");
        }
        if (age.compareTo(MAX_LIFETIME) >= 0 || remaining.isNegative() || remaining.isZero()) {
            throw new IllegalArgumentException("路线快照已失效");
        }
        Duration ttl = remaining.compareTo(MAX_LIFETIME.minus(age)) < 0
                ? remaining : MAX_LIFETIME.minus(age);

        try {
            String value = objectMapper.writeValueAsString(snapshot);
            Boolean created = redis.opsForValue().setIfAbsent(key(snapshot.routeRef()), value, ttl);
            if (!Boolean.TRUE.equals(created)) {
                throw new IllegalStateException("路线引用已存在或缓存写入未确认");
            }
        } catch (JsonProcessingException e) {
            log.error("客服路线快照序列化失败 type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("路线快照序列化失败");
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) {
                throw e;
            }
            log.error("客服路线快照缓存写入失败 type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("路线快照缓存写入失败");
        }
    }

    /**
     * 从 map-service 当前已确认条件读取同一条路线。调用方只能提供服务端取得的
     * 乘客、会话、条件版本及最新已持久化卡片的 routeRef，不能提交地图页地点。
     */
    public Optional<PassengerRouteSnapshot> find(
            String routeRef,
            long customerId,
            String conversationNo,
            long conditionVersion) {
        if (!isRandomRouteRef(routeRef) || customerId <= 0 || conversationNo == null
                || conversationNo.isBlank() || conditionVersion <= 0) {
            return Optional.empty();
        }
        PassengerRouteConditionsCache.ConfirmedConditions confirmed = conditionsCache
                .find(customerId, conversationNo, conditionVersion).orElse(null);
        if (confirmed == null) {
            return Optional.empty();
        }

        String value;
        try {
            value = redis.opsForValue().get(key(routeRef));
        } catch (RuntimeException e) {
            log.warn("读取客服路线快照缓存失败 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        try {
            PassengerRouteSnapshot snapshot = objectMapper.readValue(value, PassengerRouteSnapshot.class);
            Instant now = Instant.now();
            if (snapshot == null || !routeRef.equals(snapshot.routeRef())
                    || customerId != snapshot.customerId()
                    || !conversationNo.equals(snapshot.conversationNo())
                    || conditionVersion != snapshot.conditionVersion()
                    || !confirmed.origin().equals(snapshot.origin())
                    || !confirmed.destination().equals(snapshot.destination())
                    || !AMAP_PROVIDER.equals(snapshot.provider())
                    || snapshot.generatedAt().isAfter(now)
                    || !snapshot.expiresAt().isAfter(now)
                    || Duration.between(snapshot.generatedAt(), snapshot.expiresAt()).compareTo(MAX_LIFETIME) > 0
                    || Duration.between(snapshot.generatedAt(), now).compareTo(MAX_LIFETIME) >= 0) {
                return Optional.empty();
            }
            PassengerRouteConditionsCache.ConfirmedConditions latest = conditionsCache
                    .find(customerId, conversationNo, conditionVersion).orElse(null);
            if (!confirmed.equals(latest)) {
                return Optional.empty();
            }
            return Optional.of(snapshot);
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("客服路线快照缓存内容无效 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 重放只接收已持久化卡片的引用；版本先从缓存读取，再通过标准读取路径重验
     * 乘客、会话、当前已确认条件及有效期。旧卡片摘要无需额外保存条件版本。
     */
    public Optional<PassengerRouteSnapshot> findForReplay(String routeRef, long customerId,
                                                           String conversationNo) {
        if (!isRandomRouteRef(routeRef) || customerId <= 0 || conversationNo == null
                || conversationNo.isBlank()) {
            return Optional.empty();
        }
        String value;
        try {
            value = redis.opsForValue().get(key(routeRef));
        } catch (RuntimeException e) {
            log.warn("读取客服路线重放缓存失败 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            PassengerRouteSnapshot snapshot = objectMapper.readValue(value, PassengerRouteSnapshot.class);
            if (snapshot == null || !routeRef.equals(snapshot.routeRef())
                    || customerId != snapshot.customerId()
                    || !conversationNo.equals(snapshot.conversationNo())) {
                return Optional.empty();
            }
            return find(routeRef, customerId, conversationNo, snapshot.conditionVersion());
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("客服路线重放缓存内容无效 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String key(String routeRef) {
        return KEY_PREFIX + routeRef;
    }

    private static boolean isRandomRouteRef(String routeRef) {
        if (routeRef == null) {
            return false;
        }
        try {
            UUID uuid = UUID.fromString(routeRef);
            return uuid.version() == 4 && uuid.toString().equals(routeRef);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
