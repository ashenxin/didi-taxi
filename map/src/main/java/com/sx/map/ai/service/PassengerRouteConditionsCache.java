package com.sx.map.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.ai.model.PassengerRouteSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.List;

/**
 * map-service 内部保存乘客在本客服会话中已确认的起终点。
 *
 * 地点身份和 GCJ02 坐标只能来自地图服务的查询及乘客确认，不能从地图页、模型文本或
 * 历史卡片恢复。调用方负责确认动作、活动请求核对及条件版本递增；本类只保存已确认的当前条件。
 * 缓存丢失或失效后应在原会话重新询问两端，不能凭旧消息继续算路。
 */
@Service
public class PassengerRouteConditionsCache {
    private static final Logger log = LoggerFactory.getLogger(PassengerRouteConditionsCache.class);
    private static final String KEY_PREFIX = "map:ai:route:conditions:v1:";
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final int MAX_NUMBER_LENGTH = 64;
    private static final int MAX_WRITE_RETRIES = 3;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PassengerRouteConditionsCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入当前已确认条件。乘客修改任一端时，调用方必须先分配新的 conditionVersion；
     * Redis 写入失败不能被当成已确认成功。同版本相同地点的幂等重试复用原有效期，
     * 旧版本或同版本不同地点不能覆盖当前条件。WATCH 保证比较与写入针对同一版本。
     */
    public ConfirmedConditions saveConfirmed(long customerId,
                                             String conversationNo,
                                             long conditionVersion,
                                             PassengerRouteSnapshot.Place origin,
                                             PassengerRouteSnapshot.Place destination) {
        Instant confirmedAt = Instant.now();
        ConfirmedConditions conditions = new ConfirmedConditions(
                customerId, conversationNo, conditionVersion, origin, destination,
                confirmedAt, confirmedAt.plus(LIFETIME));
        try {
            String value = objectMapper.writeValueAsString(conditions);
            String redisKey = key(customerId, conversationNo);
            for (int attempt = 0; attempt < MAX_WRITE_RETRIES; attempt++) {
                ConfirmedConditions saved = redis.execute(new SessionCallback<>() {
                    @Override
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    public ConfirmedConditions execute(RedisOperations operations) {
                        operations.watch(redisKey);
                        String existingJson = (String) operations.opsForValue().get(redisKey);
                        if (existingJson != null) {
                            ConfirmedConditions existing = decodeExisting(existingJson);
                            if (existing.customerId() != customerId
                                    || !existing.conversationNo().equals(conversationNo)) {
                                throw new IllegalStateException("客服路线条件缓存归属无效");
                            }
                            if (existing.conditionVersion() >= conditionVersion) {
                                operations.unwatch();
                                if (existing.conditionVersion() == conditionVersion
                                        && existing.origin().equals(origin)
                                        && existing.destination().equals(destination)
                                        && existing.expiresAt().isAfter(Instant.now())) {
                                    return existing;
                                }
                                throw new IllegalStateException("旧版本或冲突的客服路线条件不能覆盖当前条件");
                            }
                        }
                        Duration remaining = Duration.between(Instant.now(), conditions.expiresAt());
                        if (remaining.isZero() || remaining.isNegative()) {
                            operations.unwatch();
                            throw new IllegalStateException("客服路线条件写入前已失效");
                        }
                        operations.multi();
                        operations.opsForValue().set(redisKey, value, remaining);
                        List<Object> results = operations.exec();
                        if (results == null) {
                            return null;
                        }
                        if (results.size() != 1 || !Boolean.TRUE.equals(results.getFirst())) {
                            throw new IllegalStateException("客服路线条件缓存写入未确认");
                        }
                        return conditions;
                    }
                });
                if (saved != null) {
                    return saved;
                }
            }
            throw new IllegalStateException("客服路线条件并发写入冲突");
        } catch (JsonProcessingException e) {
            log.error("客服路线条件序列化失败 type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("客服路线条件序列化失败", e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) {
                throw e;
            }
            log.error("客服路线条件缓存写入失败 type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("客服路线条件缓存写入失败", e);
        }
    }

    private ConfirmedConditions decodeExisting(String value) {
        try {
            ConfirmedConditions existing = objectMapper.readValue(value, ConfirmedConditions.class);
            if (existing == null) {
                throw new IllegalStateException("客服路线条件缓存内容无效");
            }
            return existing;
        } catch (JsonProcessingException | RuntimeException e) {
            throw new IllegalStateException("客服路线条件缓存内容无效", e);
        }
    }

    /**
     * 按可信乘客、会话和当前条件版本读取。旧版本不能沿用，即使 Redis 尚未清理旧值。
     * 读取不会延长有效期。
     */
    public Optional<ConfirmedConditions> find(long customerId,
                                              String conversationNo,
                                              long conditionVersion) {
        if (customerId <= 0 || !validNumber(conversationNo)
                || conditionVersion <= 0) {
            return Optional.empty();
        }

        String value;
        try {
            value = redis.opsForValue().get(key(customerId, conversationNo));
        } catch (RuntimeException e) {
            log.warn("读取客服路线条件缓存失败 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        try {
            ConfirmedConditions conditions = objectMapper.readValue(value, ConfirmedConditions.class);
            Instant now = Instant.now();
            if (conditions == null || conditions.customerId() != customerId
                    || !conversationNo.equals(conditions.conversationNo())
                    || conditions.conditionVersion() != conditionVersion
                    || conditions.confirmedAt().isAfter(now)
                    || !conditions.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            return Optional.of(conditions);
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("客服路线条件缓存内容无效 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String key(long customerId, String conversationNo) {
        return KEY_PREFIX + customerId + ":" + conversationNo;
    }

    private static boolean validNumber(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_NUMBER_LENGTH;
    }

    /**
     * 只表示乘客已确认且地图身份明确的两端；待确认地点不能构造此值用于算路。
     */
    public record ConfirmedConditions(long customerId,
                                      String conversationNo,
                                      long conditionVersion,
                                      PassengerRouteSnapshot.Place origin,
                                      PassengerRouteSnapshot.Place destination,
                                      Instant confirmedAt,
                                      Instant expiresAt) {
        public ConfirmedConditions {
            if (customerId <= 0 || !validNumber(conversationNo)
                    || conditionVersion <= 0) {
                throw new IllegalArgumentException("客服路线条件缺少乘客、会话或版本");
            }
            Objects.requireNonNull(origin, "已确认起点不能为空");
            Objects.requireNonNull(destination, "已确认终点不能为空");
            Objects.requireNonNull(confirmedAt, "确认时间不能为空");
            Objects.requireNonNull(expiresAt, "失效时间不能为空");
            Duration validity = Duration.between(confirmedAt, expiresAt);
            if (validity.isNegative() || validity.isZero() || validity.compareTo(LIFETIME) > 0) {
                throw new IllegalArgumentException("客服路线条件有效期无效");
            }
        }
    }
}
