package com.sx.map.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.ai.model.PassengerRouteIntent;
import com.sx.map.ai.model.PassengerRouteSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * AI 客服询问乘客确认起终点时使用的短时地图上下文。
 *
 * 只保存已由地图服务唯一定位、但乘客尚未确认的两端和原始提问文字；此处的地点
 * 不能用于驾车算路。后续读取必须使用 passenger-service 已持久化的最新确认问题
 * 对应的服务端 requestNo，不能相信乘客提交的请求号，也不能仅凭模型理解“对”来确认。
 * 原始业务意图单独保存；原文只供后续理解具体要求，不是地点身份或路线结论的权威来源。
 */
@Service
public class PassengerRoutePendingConfirmationCache {
    private static final Logger log = LoggerFactory.getLogger(PassengerRoutePendingConfirmationCache.class);
    private static final String KEY_PREFIX = "map:ai:route:pending-confirmation:v1:";
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final int MAX_USER_TEXT_LENGTH = 1000;
    private static final int MAX_NUMBER_LENGTH = 64;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PassengerRoutePendingConfirmationCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 在客服确认提问交付前保存待确认地点。相同 requestNo 的重试只复用完全相同的
     * 地点和原始提问，不能在同一个确认问题下悄悄替换地图身份。
     */
    public PendingConfirmation savePending(long customerId,
                                           String conversationNo,
                                           String confirmationRequestNo,
                                           long conditionVersion,
                                           PassengerRouteSnapshot.Place origin,
                                           PassengerRouteSnapshot.Place destination,
                                           PassengerRouteIntent intent,
                                           String originalUserText) {
        Instant createdAt = Instant.now();
        PendingConfirmation pending = new PendingConfirmation(
                customerId, conversationNo, confirmationRequestNo, conditionVersion,
                origin, destination, intent, originalUserText, createdAt, createdAt.plus(LIFETIME));
        Duration ttl = Duration.between(Instant.now(), pending.expiresAt());
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("客服待确认地点写入前已失效");
        }
        try {
            String value = objectMapper.writeValueAsString(pending);
            Boolean created = redis.opsForValue().setIfAbsent(
                    key(customerId, conversationNo, confirmationRequestNo), value, ttl);
            if (Boolean.TRUE.equals(created)) {
                return pending;
            }
            Optional<PendingConfirmation> existing = find(
                    customerId, conversationNo, confirmationRequestNo, conditionVersion);
            if (existing.isPresent() && sameQuestion(existing.get(), pending)) {
                return existing.get();
            }
            throw new IllegalStateException("确认请求已存在且内容不同或缓存写入未确认");
        } catch (JsonProcessingException e) {
            log.error("客服待确认地点序列化失败 type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("客服待确认地点序列化失败");
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) {
                throw e;
            }
            log.error("客服待确认地点缓存写入失败 type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("客服待确认地点缓存写入失败");
        }
    }

    /**
     * 读取当前确认问题对应的待确认地点；缓存失效或版本不符时必须重新询问起终点。
     * 读取不表示乘客已经确认，确认动作和乘客回复持久化由后续编排负责。
     */
    public Optional<PendingConfirmation> find(long customerId,
                                              String conversationNo,
                                              String confirmationRequestNo,
                                              long conditionVersion) {
        if (customerId <= 0 || !validNumber(conversationNo) || !validNumber(confirmationRequestNo)
                || conditionVersion <= 0) {
            return Optional.empty();
        }

        String value;
        try {
            value = redis.opsForValue().get(key(customerId, conversationNo, confirmationRequestNo));
        } catch (RuntimeException e) {
            log.warn("读取客服待确认地点缓存失败 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        try {
            PendingConfirmation pending = objectMapper.readValue(value, PendingConfirmation.class);
            Instant now = Instant.now();
            if (pending == null || pending.customerId() != customerId
                    || !conversationNo.equals(pending.conversationNo())
                    || !confirmationRequestNo.equals(pending.confirmationRequestNo())
                    || pending.conditionVersion() != conditionVersion
                    || pending.createdAt().isAfter(now)
                    || !pending.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            return Optional.of(pending);
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("客服待确认地点缓存内容无效 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static boolean sameQuestion(PendingConfirmation existing, PendingConfirmation submitted) {
        return existing.origin().equals(submitted.origin())
                && existing.destination().equals(submitted.destination())
                && existing.intent() == submitted.intent()
                && existing.originalUserText().equals(submitted.originalUserText());
    }

    private static String key(long customerId, String conversationNo, String confirmationRequestNo) {
        // 长度前缀避免不同的会话号和请求号包含分隔符时拼出相同 Redis 键。
        return KEY_PREFIX + customerId + ":" + conversationNo.length() + ":"
                + conversationNo + ":" + confirmationRequestNo;
    }

    private static boolean validNumber(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_NUMBER_LENGTH;
    }

    /**
     * 候选地点仍需乘客确认；只有确认成功后，才能写入已确认条件缓存。
     */
    public record PendingConfirmation(long customerId,
                                      String conversationNo,
                                      String confirmationRequestNo,
                                      long conditionVersion,
                                      PassengerRouteSnapshot.Place origin,
                                      PassengerRouteSnapshot.Place destination,
                                      PassengerRouteIntent intent,
                                      String originalUserText,
                                      Instant createdAt,
                                      Instant expiresAt) {
        public PendingConfirmation {
            if (customerId <= 0 || !validNumber(conversationNo)
                    || !validNumber(confirmationRequestNo) || conditionVersion <= 0) {
                throw new IllegalArgumentException("待确认地点缺少可信乘客、会话、请求或条件版本");
            }
            Objects.requireNonNull(origin, "待确认起点不能为空");
            Objects.requireNonNull(destination, "待确认终点不能为空");
            Objects.requireNonNull(intent, "待确认业务意图不能为空");
            if (originalUserText == null || originalUserText.isBlank()) {
                throw new IllegalArgumentException("原始提问不能为空");
            }
            originalUserText = originalUserText.strip();
            if (originalUserText.length() > MAX_USER_TEXT_LENGTH) {
                throw new IllegalArgumentException("原始提问超过长度限制");
            }
            Objects.requireNonNull(createdAt, "待确认上下文生成时间不能为空");
            Objects.requireNonNull(expiresAt, "待确认上下文失效时间不能为空");
            Duration validity = Duration.between(createdAt, expiresAt);
            if (validity.isNegative() || validity.isZero() || validity.compareTo(LIFETIME) > 0) {
                throw new IllegalArgumentException("待确认上下文有效期无效");
            }
        }
    }
}
