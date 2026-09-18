package com.sx.map.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.ai.model.PassengerRouteSnapshot.BusinessType;
import com.sx.map.ai.model.PassengerRouteIntent;
import com.sx.map.ai.model.PassengerRouteSnapshot.GeoPoint;
import com.sx.map.ai.model.PassengerRouteSnapshot.Place;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 地点身份歧义时保存本轮可选择的真实地图地点快照。
 *
 * placeChoiceId 只表示本 taskNo 中的一项；它不是 POI ID，也不授权读取地点。
 * 选择须再次绑定认证乘客、会话、条件版本和请求版本。返回的可信地图地点仅供
 * map-service 继续原始意图；客户端和模型只接收 summaries() 的可读信息。
 */
@Service
public class PassengerRoutePlaceChoiceCache {
    private static final Logger log = LoggerFactory.getLogger(PassengerRoutePlaceChoiceCache.class);
    private static final String KEY_PREFIX = "map:ai:route:place-choices:v1:";
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final int MAX_CHOICES = 10;
    private static final int MAX_NUMBER_LENGTH = 64;
    private static final int MAX_USER_TEXT_LENGTH = 1000;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PassengerRouteConditionsCache conditionsCache;

    public PassengerRoutePlaceChoiceCache(StringRedisTemplate redis,
                                          ObjectMapper objectMapper,
                                          PassengerRouteConditionsCache conditionsCache) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.conditionsCache = conditionsCache;
    }

    /**
     * 调用方只能传入本次地图查询得到的候选；起终点选择后仍须复述完整两端等待乘客确认。
     * WAYPOINT 选择必须绑定仍有效的已确认起终点，选择本身不是经过路线的证据。
     */
    public ChoiceTask save(long customerId,
                           String conversationNo,
                           long conditionVersion,
                           long requestVersion,
                           ChoiceRole role,
                           PassengerRouteIntent intent,
                           String originalUserText,
                           Place origin,
                           Place destination,
                           List<PlaceCandidate> candidates) {
        if (customerId <= 0 || !validNumber(conversationNo)
                || conditionVersion <= 0 || requestVersion <= 0) {
            throw new IllegalArgumentException("地点选择缺少可信会话或版本");
        }
        Objects.requireNonNull(role, "地点选择角色不能为空");
        Objects.requireNonNull(intent, "待接续意图不能为空");
        if (candidates == null || candidates.isEmpty() || candidates.size() > MAX_CHOICES) {
            throw new IllegalArgumentException("地点选择候选数量无效");
        }
        if (role == ChoiceRole.WAYPOINT) {
            PassengerRouteConditionsCache.ConfirmedConditions confirmed = conditionsCache
                    .find(customerId, conversationNo, conditionVersion)
                    .orElseThrow(() -> new IllegalStateException("已确认起终点不存在或已失效"));
            if (!confirmed.origin().equals(origin) || !confirmed.destination().equals(destination)) {
                throw new IllegalStateException("地点选择与当前已确认起终点不符");
            }
        }

        List<Choice> choices = new ArrayList<>(candidates.size());
        for (PlaceCandidate candidate : candidates) {
            if (candidate == null || (role == ChoiceRole.WAYPOINT) != (candidate.businessType() != null)) {
                throw new IllegalArgumentException("地点候选角色或业务类型不符");
            }
            choices.add(new Choice(newOpaqueId("PC-"), candidate));
        }
        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plus(LIFETIME);
        if (role == ChoiceRole.WAYPOINT) {
            PassengerRouteConditionsCache.ConfirmedConditions current = conditionsCache
                    .find(customerId, conversationNo, conditionVersion)
                    .orElseThrow(() -> new IllegalStateException("保存地点选项前起终点已失效"));
            if (!current.origin().equals(origin) || !current.destination().equals(destination)) {
                throw new IllegalStateException("保存地点选项前起终点发生变更");
            }
            if (current.expiresAt().isBefore(expiresAt)) {
                expiresAt = current.expiresAt();
            }
        }
        ChoiceTask task = new ChoiceTask(newOpaqueId("AIT-"), customerId, conversationNo,
                conditionVersion, requestVersion, role, intent, originalUserText,
                origin, destination, choices, createdAt, expiresAt);
        Duration ttl = Duration.between(Instant.now(), task.expiresAt());
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("地点选项写入前已失效");
        }
        try {
            Boolean created = redis.opsForValue().setIfAbsent(
                    key(task.taskNo()), objectMapper.writeValueAsString(task), ttl);
            if (!Boolean.TRUE.equals(created)) {
                throw new IllegalStateException("地点选项任务已存在或缓存写入未确认");
            }
            return task;
        } catch (JsonProcessingException e) {
            log.error("客服地点选项序列化失败 type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("客服地点选项序列化失败", e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) {
                throw e;
            }
            log.error("客服地点选项缓存写入失败 type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("客服地点选项缓存写入失败", e);
        }
    }

    /** 归属、版本或有效期不匹配都视为不存在；不从历史消息恢复过期 POI。 */
    public Optional<ChoiceTask> find(String taskNo,
                                     long customerId,
                                     String conversationNo,
                                     long conditionVersion,
                                     long requestVersion) {
        if (!validOpaqueId(taskNo, "AIT-") || customerId <= 0 || !validNumber(conversationNo)
                || conditionVersion <= 0 || requestVersion <= 0) {
            return Optional.empty();
        }
        String value;
        try {
            value = redis.opsForValue().get(key(taskNo));
        } catch (RuntimeException e) {
            log.warn("读取客服地点选项失败 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            ChoiceTask task = objectMapper.readValue(value, ChoiceTask.class);
            Instant now = Instant.now();
            if (task == null || !taskNo.equals(task.taskNo())
                    || task.customerId() != customerId
                    || !conversationNo.equals(task.conversationNo())
                    || task.conditionVersion() != conditionVersion
                    || task.requestVersion() != requestVersion
                    || task.createdAt().isAfter(now) || !task.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            if (task.role() == ChoiceRole.WAYPOINT) {
                PassengerRouteConditionsCache.ConfirmedConditions current = conditionsCache
                        .find(customerId, conversationNo, conditionVersion).orElse(null);
                if (current == null || !current.origin().equals(task.origin())
                        || !current.destination().equals(task.destination())) {
                    return Optional.empty();
                }
            }
            return Optional.of(task);
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("客服地点选项内容无效 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public Optional<SelectedChoice> select(String taskNo,
                                           String placeChoiceId,
                                           long customerId,
                                           String conversationNo,
                                           long conditionVersion,
                                           long requestVersion) {
        if (!validOpaqueId(placeChoiceId, "PC-")) {
            return Optional.empty();
        }
        ChoiceTask task = find(taskNo, customerId, conversationNo, conditionVersion, requestVersion)
                .orElse(null);
        if (task == null) {
            return Optional.empty();
        }
        for (Choice choice : task.choices()) {
            if (placeChoiceId.equals(choice.placeChoiceId())) {
                return Optional.of(new SelectedChoice(task.taskNo(), task.role(), task.intent(),
                        task.originalUserText(), task.origin(), task.destination(),
                        choice.candidate(), task.expiresAt()));
            }
        }
        return Optional.empty();
    }

    private static String key(String taskNo) {
        return KEY_PREFIX + taskNo;
    }

    private static boolean validNumber(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_NUMBER_LENGTH;
    }

    private static String newOpaqueId(String prefix) {
        return prefix + UUID.randomUUID();
    }

    private static boolean validOpaqueId(String value, String prefix) {
        if (value == null || !value.startsWith(prefix)) {
            return false;
        }
        String raw = value.substring(prefix.length());
        try {
            UUID uuid = UUID.fromString(raw);
            return uuid.version() == 4 && uuid.toString().equals(raw);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public enum ChoiceRole {
        ORIGIN, DESTINATION, WAYPOINT
    }

    /**
     * WAYPOINT 的导航点是受控地图原料，仍须经过路线验证后才能产生 PASSED。
     * 收费站缺入口出口时允许只保存地图中心点供后续尝试算路；中心点本身不是经过证据。
     */
    public record PlaceCandidate(Place place,
                                 BusinessType businessType,
                                 List<GeoPoint> navigationPoints,
                                 String displayType) {
        public PlaceCandidate {
            Objects.requireNonNull(place, "地点候选缺少地图地点");
            navigationPoints = navigationPoints == null ? List.of() : List.copyOf(navigationPoints);
            if (businessType == null && !navigationPoints.isEmpty()) {
                throw new IllegalArgumentException("普通起终点不能携带途经导航点");
            }
            if (businessType != null && businessType != BusinessType.TOLL_STATION
                    && navigationPoints.isEmpty()) {
                throw new IllegalArgumentException("服务区或加油站缺少可通行导航点");
            }
            if (displayType != null && displayType.length() > 100) {
                throw new IllegalArgumentException("地点展示类型超过长度限制");
            }
        }
    }

    public record Choice(String placeChoiceId, PlaceCandidate candidate) {
        public Choice {
            if (!validOpaqueId(placeChoiceId, "PC-")) {
                throw new IllegalArgumentException("地点选项编号无效");
            }
            Objects.requireNonNull(candidate, "地点选项缺少地图候选");
        }
    }

    public record ChoiceSummary(String placeChoiceId,
                                String name,
                                String displayType,
                                String city,
                                String address,
                                BusinessType businessType,
                                Instant expiresAt) {
    }

    public record ChoiceTask(String taskNo,
                             long customerId,
                             String conversationNo,
                             long conditionVersion,
                             long requestVersion,
                             ChoiceRole role,
                             PassengerRouteIntent intent,
                             String originalUserText,
                             Place origin,
                             Place destination,
                             List<Choice> choices,
                             Instant createdAt,
                             Instant expiresAt) {
        public ChoiceTask {
            if (!validOpaqueId(taskNo, "AIT-") || customerId <= 0 || !validNumber(conversationNo)
                    || conditionVersion <= 0 || requestVersion <= 0) {
                throw new IllegalArgumentException("地点选择任务缺少可信身份或版本");
            }
            Objects.requireNonNull(role, "地点选择角色不能为空");
            Objects.requireNonNull(intent, "待接续意图不能为空");
            if (originalUserText == null || originalUserText.isBlank()) {
                throw new IllegalArgumentException("地点选择任务缺少原始提问");
            }
            originalUserText = originalUserText.strip();
            if (originalUserText.length() > MAX_USER_TEXT_LENGTH) {
                throw new IllegalArgumentException("原始提问超过长度限制");
            }
            if (role == ChoiceRole.ORIGIN && origin != null
                    || role == ChoiceRole.DESTINATION && destination != null
                    || role == ChoiceRole.WAYPOINT && (origin == null || destination == null)) {
                throw new IllegalArgumentException("地点选择任务端点状态与角色不符");
            }
            if (choices == null || choices.isEmpty() || choices.size() > MAX_CHOICES) {
                throw new IllegalArgumentException("地点选择任务候选数量无效");
            }
            choices = List.copyOf(choices);
            Set<String> ids = new HashSet<>();
            for (Choice choice : choices) {
                if (!ids.add(choice.placeChoiceId())
                        || (role == ChoiceRole.WAYPOINT) != (choice.candidate().businessType() != null)) {
                    throw new IllegalArgumentException("地点选项重复或业务类型不符");
                }
            }
            Objects.requireNonNull(createdAt, "地点选项生成时间不能为空");
            Objects.requireNonNull(expiresAt, "地点选项失效时间不能为空");
            Duration validity = Duration.between(createdAt, expiresAt);
            if (validity.isZero() || validity.isNegative() || validity.compareTo(LIFETIME) > 0) {
                throw new IllegalArgumentException("地点选项有效期无效");
            }
        }

        /** 仅此摘要可用于 place.choices；完整地点快照留在 map-service。 */
        public List<ChoiceSummary> summaries() {
            return choices.stream().map(choice -> {
                PlaceCandidate candidate = choice.candidate();
                Place place = candidate.place();
                return new ChoiceSummary(choice.placeChoiceId(), place.name(),
                        candidate.displayType(), place.city(), place.address(),
                        candidate.businessType(), expiresAt);
            }).toList();
        }
    }

    public record SelectedChoice(String taskNo,
                                 ChoiceRole role,
                                 PassengerRouteIntent intent,
                                 String originalUserText,
                                 Place origin,
                                 Place destination,
                                 PlaceCandidate candidate,
                                 Instant expiresAt) {
    }
}
