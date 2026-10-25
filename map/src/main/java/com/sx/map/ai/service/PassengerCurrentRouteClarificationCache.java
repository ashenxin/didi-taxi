package com.sx.map.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.ai.model.CurrentRouteCheckMode;
import com.sx.map.ai.model.PassengerRouteSnapshot;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.PlaceCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 保存“经过”含义澄清所需的可信服务端上下文。
 *
 * 仅用于兼容已持久化的旧会话；新请求不再生成含义选项，只问“是否经过”时由
 * {@link PassengerCurrentRouteCheckService} 直接给出复合关系结论。
 *
 * 客户端只能提交 taskNo 与缓存中允许的枚举选项；地点、坐标、routeRef、路线条件和
 * 原始提问均从服务端上下文恢复。该任务不授权读取路线，恢复时仍要重新验证 routeRef。
 */
@Service
public class PassengerCurrentRouteClarificationCache {
    private static final Logger log = LoggerFactory.getLogger(PassengerCurrentRouteClarificationCache.class);
    private static final String KEY_PREFIX = "map:ai:route:current-check-clarification:v1:";
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(5);
    private static final int MAX_NUMBER_LENGTH = 64;
    private static final int MAX_USER_TEXT_LENGTH = 1000;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PassengerCurrentRouteClarificationCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public ClarificationTask save(PassengerRouteSnapshot snapshot,
                                  long requestVersion,
                                  String originalUserText,
                                  PlaceCandidate candidate,
                                  List<CurrentRouteCheckMode> allowedModes) {
        Objects.requireNonNull(snapshot, "当前路线不能为空");
        Objects.requireNonNull(candidate, "澄清任务缺少可信地点");
        String userText = requiredText(originalUserText);
        List<CurrentRouteCheckMode> modes = validatedModes(allowedModes);
        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plus(MAX_LIFETIME);
        if (snapshot.expiresAt().isBefore(expiresAt)) {
            expiresAt = snapshot.expiresAt();
        }
        ClarificationTask task = new ClarificationTask(newOpaqueId(), snapshot.routeRef(),
                snapshot.customerId(), snapshot.conversationNo(), snapshot.conditionVersion(),
                requestVersion, userText, candidate, modes, createdAt, expiresAt);
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("核查含义选项写入前路线已失效");
        }
        try {
            Boolean created = redis.opsForValue().setIfAbsent(
                    key(task.taskNo()), objectMapper.writeValueAsString(task), ttl);
            if (!Boolean.TRUE.equals(created)) {
                throw new IllegalStateException("核查含义任务已存在或缓存写入未确认");
            }
            return task;
        } catch (JsonProcessingException e) {
            log.error("核查含义任务序列化失败 type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("核查含义任务序列化失败", e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) throw e;
            log.error("核查含义任务缓存写入失败 type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("核查含义任务缓存写入失败", e);
        }
    }

    public Optional<SelectedMode> select(String taskNo,
                                         String selectedMode,
                                         long customerId,
                                         String conversationNo,
                                         String routeRef,
                                         long choiceRequestVersion,
                                         long currentRequestVersion) {
        if (currentRequestVersion <= choiceRequestVersion) return Optional.empty();
        CurrentRouteCheckMode mode;
        try {
            mode = CurrentRouteCheckMode.valueOf(selectedMode);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        ClarificationTask task = find(taskNo, customerId, conversationNo, routeRef,
                choiceRequestVersion).orElse(null);
        if (task == null || !task.allowedModes().contains(mode)) {
            return Optional.empty();
        }
        return Optional.of(new SelectedMode(task.candidate(), mode, task.originalUserText(),
                task.conditionVersion(), task.expiresAt()));
    }

    private Optional<ClarificationTask> find(String taskNo,
                                             long customerId,
                                             String conversationNo,
                                             String routeRef,
                                             long requestVersion) {
        if (!validOpaqueId(taskNo) || customerId <= 0 || !validNumber(conversationNo)
                || !validNumber(routeRef) || requestVersion <= 0) {
            return Optional.empty();
        }
        String value;
        try {
            value = redis.opsForValue().get(key(taskNo));
        } catch (RuntimeException e) {
            log.warn("读取核查含义任务失败 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            ClarificationTask task = objectMapper.readValue(value, ClarificationTask.class);
            Instant now = Instant.now();
            if (task == null || !taskNo.equals(task.taskNo()) || task.customerId() != customerId
                    || !conversationNo.equals(task.conversationNo()) || !routeRef.equals(task.routeRef())
                    || task.requestVersion() != requestVersion || task.createdAt().isAfter(now)
                    || !task.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            return Optional.of(task);
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("核查含义任务内容无效 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static List<CurrentRouteCheckMode> validatedModes(List<CurrentRouteCheckMode> modes) {
        if (modes == null || modes.size() < 2 || modes.size() > 3
                || modes.stream().anyMatch(Objects::isNull)
                || modes.contains(CurrentRouteCheckMode.UNSPECIFIED)
                || new HashSet<>(modes).size() != modes.size()) {
            throw new IllegalArgumentException("核查含义选项无效");
        }
        return List.copyOf(modes);
    }

    private static String requiredText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("澄清任务缺少原始提问");
        String normalized = value.strip();
        if (normalized.length() > MAX_USER_TEXT_LENGTH) {
            throw new IllegalArgumentException("原始提问超过长度限制");
        }
        return normalized;
    }

    private static String key(String taskNo) {
        return KEY_PREFIX + taskNo;
    }

    private static String newOpaqueId() {
        return "RCT-" + UUID.randomUUID();
    }

    private static boolean validOpaqueId(String value) {
        if (value == null || !value.startsWith("RCT-")) return false;
        String raw = value.substring(4);
        try {
            UUID uuid = UUID.fromString(raw);
            return uuid.version() == 4 && uuid.toString().equals(raw);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean validNumber(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_NUMBER_LENGTH;
    }

    public record ClarificationTask(String taskNo,
                                    String routeRef,
                                    long customerId,
                                    String conversationNo,
                                    long conditionVersion,
                                    long requestVersion,
                                    String originalUserText,
                                    PlaceCandidate candidate,
                                    List<CurrentRouteCheckMode> allowedModes,
                                    Instant createdAt,
                                    Instant expiresAt) {
        public ClarificationTask {
            if (!validOpaqueId(taskNo) || !validNumber(routeRef) || customerId <= 0
                    || !validNumber(conversationNo) || conditionVersion <= 0 || requestVersion <= 0) {
                throw new IllegalArgumentException("核查含义任务缺少可信身份或版本");
            }
            originalUserText = requiredText(originalUserText);
            Objects.requireNonNull(candidate, "核查含义任务缺少地点");
            allowedModes = validatedModes(allowedModes);
            Objects.requireNonNull(createdAt, "核查含义任务缺少生成时间");
            Objects.requireNonNull(expiresAt, "核查含义任务缺少失效时间");
            Duration validity = Duration.between(createdAt, expiresAt);
            if (validity.isZero() || validity.isNegative() || validity.compareTo(MAX_LIFETIME) > 0) {
                throw new IllegalArgumentException("核查含义任务有效期无效");
            }
        }
    }

    public record SelectedMode(PlaceCandidate candidate,
                               CurrentRouteCheckMode mode,
                               String originalUserText,
                               long conditionVersion,
                               Instant expiresAt) {
    }
}
