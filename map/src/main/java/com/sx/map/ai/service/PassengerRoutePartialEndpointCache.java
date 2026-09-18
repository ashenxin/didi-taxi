package com.sx.map.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.ai.model.PassengerRouteIntent;
import com.sx.map.ai.model.PassengerRouteSnapshot.Place;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 客服只取得一个明确端点时，短时保留该地图地点、业务意图和原始提问。
 *
 * 缺少另一端时不得算路。下一轮只能用 passenger-service 最新已持久化追问的
 * 服务端 requestNo 读取本记录；缓存失效后必须重新询问，不能用聊天摘要恢复坐标。
 */
@Service
public class PassengerRoutePartialEndpointCache {
    private static final Logger log = LoggerFactory.getLogger(PassengerRoutePartialEndpointCache.class);
    private static final String KEY_PREFIX = "map:ai:route:partial-endpoint:v1:";
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final int MAX_NUMBER_LENGTH = 64;
    private static final int MAX_USER_TEXT_LENGTH = 1000;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PassengerRoutePartialEndpointCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public PartialEndpoint save(long customerId,
                                String conversationNo,
                                String followUpRequestNo,
                                long conditionVersion,
                                EndpointRole knownRole,
                                Place knownPlace,
                                PassengerRouteIntent intent,
                                String originalUserText) {
        Instant createdAt = Instant.now();
        PartialEndpoint pending = new PartialEndpoint(customerId, conversationNo, followUpRequestNo,
                conditionVersion, knownRole, knownPlace, intent, originalUserText,
                createdAt, createdAt.plus(LIFETIME));
        Duration ttl = Duration.between(Instant.now(), pending.expiresAt());
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("客服单端地点写入前已失效");
        }
        try {
            Boolean created = redis.opsForValue().setIfAbsent(
                    key(customerId, conversationNo, followUpRequestNo),
                    objectMapper.writeValueAsString(pending), ttl);
            if (Boolean.TRUE.equals(created)) {
                return pending;
            }
            Optional<PartialEndpoint> existing = find(
                    customerId, conversationNo, followUpRequestNo, conditionVersion);
            if (existing.isPresent() && existing.get().knownRole() == knownRole
                    && existing.get().knownPlace().equals(knownPlace)
                    && existing.get().intent() == intent
                    && existing.get().originalUserText().equals(pending.originalUserText())) {
                return existing.get();
            }
            throw new IllegalStateException("补齐地点请求已存在且内容不同或缓存写入未确认");
        } catch (JsonProcessingException e) {
            log.error("客服单端地点序列化失败 type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("客服单端地点序列化失败", e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) {
                throw e;
            }
            log.error("客服单端地点缓存写入失败 type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("客服单端地点缓存写入失败", e);
        }
    }

    public Optional<PartialEndpoint> find(long customerId,
                                          String conversationNo,
                                          String followUpRequestNo,
                                          long conditionVersion) {
        if (customerId <= 0 || !validNumber(conversationNo) || !validNumber(followUpRequestNo)
                || conditionVersion <= 0) {
            return Optional.empty();
        }
        String value;
        try {
            value = redis.opsForValue().get(key(customerId, conversationNo, followUpRequestNo));
        } catch (RuntimeException e) {
            log.warn("读取客服单端地点缓存失败 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            PartialEndpoint partial = objectMapper.readValue(value, PartialEndpoint.class);
            Instant now = Instant.now();
            if (partial == null || partial.customerId() != customerId
                    || !conversationNo.equals(partial.conversationNo())
                    || !followUpRequestNo.equals(partial.followUpRequestNo())
                    || partial.conditionVersion() != conditionVersion
                    || partial.createdAt().isAfter(now) || !partial.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            return Optional.of(partial);
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("客服单端地点缓存内容无效 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String key(long customerId, String conversationNo, String requestNo) {
        return KEY_PREFIX + customerId + ":" + conversationNo.length() + ":"
                + conversationNo + ":" + requestNo;
    }

    private static boolean validNumber(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_NUMBER_LENGTH;
    }

    public enum EndpointRole {
        ORIGIN, DESTINATION
    }

    public record PartialEndpoint(long customerId,
                                  String conversationNo,
                                  String followUpRequestNo,
                                  long conditionVersion,
                                  EndpointRole knownRole,
                                  Place knownPlace,
                                  PassengerRouteIntent intent,
                                  String originalUserText,
                                  Instant createdAt,
                                  Instant expiresAt) {
        public PartialEndpoint {
            if (customerId <= 0 || !validNumber(conversationNo)
                    || !validNumber(followUpRequestNo) || conditionVersion <= 0) {
                throw new IllegalArgumentException("单端地点缺少可信乘客、会话、请求或条件版本");
            }
            Objects.requireNonNull(knownRole, "已知端点角色不能为空");
            Objects.requireNonNull(knownPlace, "已知地点不能为空");
            Objects.requireNonNull(intent, "单端地点原始业务意图不能为空");
            if (originalUserText == null || originalUserText.isBlank()) {
                throw new IllegalArgumentException("原始提问不能为空");
            }
            originalUserText = originalUserText.strip();
            if (originalUserText.length() > MAX_USER_TEXT_LENGTH) {
                throw new IllegalArgumentException("原始提问超过长度限制");
            }
            Objects.requireNonNull(createdAt, "单端地点生成时间不能为空");
            Objects.requireNonNull(expiresAt, "单端地点失效时间不能为空");
            Duration duration = Duration.between(createdAt, expiresAt);
            if (duration.isZero() || duration.isNegative() || duration.compareTo(LIFETIME) > 0) {
                throw new IllegalArgumentException("单端地点有效期无效");
            }
        }
    }
}
