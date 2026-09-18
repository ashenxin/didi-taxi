package com.sx.map.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.map.ai.model.PassengerRouteSnapshot;
import com.sx.map.ai.model.PassengerRouteSnapshot.BusinessType;
import com.sx.map.ai.model.PassengerRouteSnapshot.PassageVerification;
import com.sx.map.ai.model.PassengerRouteSnapshot.Place;
import com.sx.map.ai.model.PassengerRouteSnapshot.ViaPlace;
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
 * 乘客主动查询可选收费站路线时使用的短时选择索引。
 *
 * 每个 routeOptionId 只引用 map-service 已缓存的完整且经过验证的路线快照。
 * 选择时再次校验乘客、会话、条件和请求版本、选项成员、路线快照及有效期；
 * 不用选项摘要重新算路，也不把尚未选择的路线当作客服当前路线。
 */
@Service
public class PassengerRouteOptionCache {
    private static final Logger log = LoggerFactory.getLogger(PassengerRouteOptionCache.class);
    private static final String KEY_PREFIX = "map:ai:route:options:v1:";
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final int MAX_OPTIONS = 10;
    private static final int MAX_NUMBER_LENGTH = 64;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PassengerRouteConditionsCache conditionsCache;
    private final PassengerRouteSnapshotCache snapshotCache;

    public PassengerRouteOptionCache(StringRedisTemplate redis,
                                     ObjectMapper objectMapper,
                                     PassengerRouteConditionsCache conditionsCache,
                                     PassengerRouteSnapshotCache snapshotCache) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.conditionsCache = conditionsCache;
        this.snapshotCache = snapshotCache;
    }

    /**
     * 调用方须先生成并缓存每条可行路线。DEFAULT 可附带针对一个收费站单独验证的事实；
     * 非默认方案直接使用 VIA_PLACE 快照内已经通过的收费站验证。不得把原始 POI 列表传入。
     */
    public RouteOptionTask save(long customerId,
                                String conversationNo,
                                long conditionVersion,
                                long requestVersion,
                                List<RouteOptionCandidate> candidates) {
        if (customerId <= 0 || !validNumber(conversationNo)
                || conditionVersion <= 0 || requestVersion <= 0) {
            throw new IllegalArgumentException("路线方案缺少可信会话或版本");
        }
        if (candidates == null || candidates.isEmpty() || candidates.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException("路线方案数量无效");
        }
        PassengerRouteConditionsCache.ConfirmedConditions confirmed = conditionsCache
                .find(customerId, conversationNo, conditionVersion)
                .orElseThrow(() -> new IllegalStateException("已确认起终点不存在或已失效"));

        List<RouteOption> options = new ArrayList<>(candidates.size());
        Instant earliestExpiry = confirmed.expiresAt();
        Set<String> routeRefs = new HashSet<>();
        int defaultCount = 0;
        for (RouteOptionCandidate candidate : candidates) {
            if (candidate == null || !routeRefs.add(candidate.routeRef())) {
                throw new IllegalArgumentException("路线方案为空或重复引用同一条路线");
            }
            PassengerRouteSnapshot snapshot = snapshotCache.find(candidate.routeRef(), customerId,
                    conversationNo, conditionVersion)
                    .orElseThrow(() -> new IllegalStateException("路线方案缺少有效完整快照"));
            ViaPlace via;
            PassageVerification verification;
            if (candidate.isMapDefault()) {
                defaultCount++;
                if (snapshot.routeKind() != PassengerRouteSnapshot.RouteKind.DEFAULT) {
                    throw new IllegalArgumentException("默认方案必须引用真实默认路线");
                }
                via = candidate.verifiedDefaultStation();
                verification = candidate.defaultStationVerification();
            } else {
                if (snapshot.routeKind() != PassengerRouteSnapshot.RouteKind.VIA_PLACE
                        || snapshot.via().businessType() != BusinessType.TOLL_STATION) {
                    throw new IllegalArgumentException("非默认收费站方案必须引用已验证途经路线");
                }
                via = snapshot.via();
                verification = snapshot.passageVerification();
            }
            if (via != null && via.businessType() != BusinessType.TOLL_STATION) {
                throw new IllegalArgumentException("开放式路线方案只支持收费站");
            }
            if (snapshot.expiresAt().isBefore(earliestExpiry)) {
                earliestExpiry = snapshot.expiresAt();
            }
            options.add(new RouteOption(newOpaqueId("RO-"), snapshot.routeRef(), via,
                    verification, snapshot.distanceMeters(), snapshot.durationSeconds(),
                    candidate.isMapDefault(), snapshot.expiresAt()));
        }
        if (defaultCount != 1) {
            throw new IllegalArgumentException("路线方案必须恰有一条地图默认方案");
        }

        Instant createdAt = Instant.now();
        Instant maxExpiry = createdAt.plus(LIFETIME);
        Instant expiresAt = earliestExpiry.isBefore(maxExpiry) ? earliestExpiry : maxExpiry;
        RouteOptionTask task = new RouteOptionTask(newOpaqueId("AIT-"), customerId,
                conversationNo, conditionVersion, requestVersion, confirmed.origin(),
                confirmed.destination(), options, createdAt, expiresAt);
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("路线方案写入前已失效");
        }
        PassengerRouteConditionsCache.ConfirmedConditions current = conditionsCache
                .find(customerId, conversationNo, conditionVersion)
                .orElseThrow(() -> new IllegalStateException("保存路线方案前起终点已失效"));
        if (!confirmed.equals(current)) {
            throw new IllegalStateException("保存路线方案前起终点发生变更");
        }
        try {
            Boolean created = redis.opsForValue().setIfAbsent(
                    key(task.taskNo()), objectMapper.writeValueAsString(task), ttl);
            if (!Boolean.TRUE.equals(created)) {
                throw new IllegalStateException("路线方案任务已存在或缓存写入未确认");
            }
            return task;
        } catch (JsonProcessingException e) {
            log.error("客服路线方案序列化失败 type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("客服路线方案序列化失败", e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) {
                throw e;
            }
            log.error("客服路线方案缓存写入失败 type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("客服路线方案缓存写入失败", e);
        }
    }

    /** 仅有效的整组方案可被继续选择；任何完整路线丢失时整组方案失效。 */
    public Optional<RouteOptionTask> find(String taskNo,
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
            log.warn("读取客服路线方案失败 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            RouteOptionTask task = objectMapper.readValue(value, RouteOptionTask.class);
            Instant now = Instant.now();
            if (task == null || !taskNo.equals(task.taskNo()) || task.customerId() != customerId
                    || !conversationNo.equals(task.conversationNo())
                    || task.conditionVersion() != conditionVersion
                    || task.requestVersion() != requestVersion
                    || task.createdAt().isAfter(now) || !task.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            PassengerRouteConditionsCache.ConfirmedConditions current = conditionsCache
                    .find(customerId, conversationNo, conditionVersion).orElse(null);
            if (current == null || !task.origin().equals(current.origin())
                    || !task.destination().equals(current.destination())) {
                return Optional.empty();
            }
            for (RouteOption option : task.options()) {
                PassengerRouteSnapshot snapshot = snapshotCache.find(option.routeRef(), customerId,
                        conversationNo, conditionVersion)
                        .orElse(null);
                if (!matchesSnapshot(option, snapshot) || option.snapshotExpiresAt().isBefore(task.expiresAt())) {
                    return Optional.empty();
                }
            }
            PassengerRouteConditionsCache.ConfirmedConditions latest = conditionsCache
                    .find(customerId, conversationNo, conditionVersion).orElse(null);
            if (!current.equals(latest)) {
                return Optional.empty();
            }
            return Optional.of(task);
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("客服路线方案内容无效 type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 选中后只解析缓存中的同一条路线，不重新调用高德或模型。 */
    public Optional<SelectedRouteOption> select(String taskNo,
                                                String routeOptionId,
                                                long customerId,
                                                String conversationNo,
                                                long conditionVersion,
                                                long requestVersion) {
        if (!validOpaqueId(routeOptionId, "RO-")) {
            return Optional.empty();
        }
        RouteOptionTask task = find(taskNo, customerId, conversationNo,
                conditionVersion, requestVersion).orElse(null);
        if (task == null) {
            return Optional.empty();
        }
        for (RouteOption option : task.options()) {
            if (routeOptionId.equals(option.routeOptionId())) {
                PassengerRouteSnapshot snapshot = snapshotCache.find(option.routeRef(), customerId,
                        conversationNo, conditionVersion)
                        .orElse(null);
                if (!matchesSnapshot(option, snapshot) || !task.expiresAt().isAfter(Instant.now())) {
                    return Optional.empty();
                }
                return Optional.of(new SelectedRouteOption(task.taskNo(), option, snapshot,
                        task.expiresAt()));
            }
        }
        return Optional.empty();
    }

    private static boolean matchesSnapshot(RouteOption option, PassengerRouteSnapshot snapshot) {
        if (snapshot == null || option.distanceMeters() != snapshot.distanceMeters()
                || option.durationSeconds() != snapshot.durationSeconds()
                || !option.snapshotExpiresAt().equals(snapshot.expiresAt())) {
            return false;
        }
        if (option.isMapDefault()) {
            return snapshot.routeKind() == PassengerRouteSnapshot.RouteKind.DEFAULT;
        }
        return snapshot.routeKind() == PassengerRouteSnapshot.RouteKind.VIA_PLACE
                && Objects.equals(option.via(), snapshot.via())
                && Objects.equals(option.verification(), snapshot.passageVerification());
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

    public record RouteOptionCandidate(String routeRef,
                                       boolean isMapDefault,
                                       ViaPlace verifiedDefaultStation,
                                       PassageVerification defaultStationVerification) {
        public RouteOptionCandidate {
            if (routeRef == null || routeRef.isBlank()) {
                throw new IllegalArgumentException("路线方案缺少完整路线引用");
            }
            if (isMapDefault) {
                if ((verifiedDefaultStation == null) != (defaultStationVerification == null)) {
                    throw new IllegalArgumentException("默认路线的收费站与验证证据必须同时存在");
                }
            } else if (verifiedDefaultStation != null || defaultStationVerification != null) {
                throw new IllegalArgumentException("非默认路线从完整快照读取途经证据");
            }
        }
    }

    public record RouteOption(String routeOptionId,
                              String routeRef,
                              ViaPlace via,
                              PassageVerification verification,
                              long distanceMeters,
                              long durationSeconds,
                              boolean isMapDefault,
                              Instant snapshotExpiresAt) {
        public RouteOption {
            if (!validOpaqueId(routeOptionId, "RO-") || routeRef == null || routeRef.isBlank()
                    || distanceMeters < 0 || durationSeconds < 0) {
                throw new IllegalArgumentException("路线选项编号、引用或指标无效");
            }
            if ((via == null) != (verification == null)
                    || via != null && via.businessType() != BusinessType.TOLL_STATION) {
                throw new IllegalArgumentException("路线选项收费站与验证证据不符");
            }
            Objects.requireNonNull(snapshotExpiresAt, "路线快照失效时间不能为空");
        }
    }

    public record RouteOptionSummary(String routeOptionId,
                                     String stationName,
                                     BusinessType businessType,
                                     long durationSeconds,
                                     boolean isMapDefault,
                                     Instant expiresAt) {
    }

    public record RouteOptionTask(String taskNo,
                                  long customerId,
                                  String conversationNo,
                                  long conditionVersion,
                                  long requestVersion,
                                  Place origin,
                                  Place destination,
                                  List<RouteOption> options,
                                  Instant createdAt,
                                  Instant expiresAt) {
        public RouteOptionTask {
            if (!validOpaqueId(taskNo, "AIT-") || customerId <= 0 || !validNumber(conversationNo)
                    || conditionVersion <= 0 || requestVersion <= 0) {
                throw new IllegalArgumentException("路线方案任务缺少可信身份或版本");
            }
            Objects.requireNonNull(origin, "路线方案起点不能为空");
            Objects.requireNonNull(destination, "路线方案终点不能为空");
            if (options == null || options.isEmpty() || options.size() > MAX_OPTIONS) {
                throw new IllegalArgumentException("路线方案数量无效");
            }
            options = List.copyOf(options);
            Set<String> optionIds = new HashSet<>();
            Set<String> routeRefs = new HashSet<>();
            int defaultCount = 0;
            for (RouteOption option : options) {
                if (!optionIds.add(option.routeOptionId()) || !routeRefs.add(option.routeRef())) {
                    throw new IllegalArgumentException("路线方案编号或路线引用重复");
                }
                if (option.isMapDefault()) {
                    defaultCount++;
                } else if (option.via() == null || option.verification() == null) {
                    throw new IllegalArgumentException("非默认方案缺少收费站经过证据");
                }
            }
            if (defaultCount != 1) {
                throw new IllegalArgumentException("路线方案必须恰有一条地图默认方案");
            }
            Objects.requireNonNull(createdAt, "路线方案生成时间不能为空");
            Objects.requireNonNull(expiresAt, "路线方案失效时间不能为空");
            Duration validity = Duration.between(createdAt, expiresAt);
            if (validity.isNegative() || validity.isZero() || validity.compareTo(LIFETIME) > 0) {
                throw new IllegalArgumentException("路线方案有效期无效");
            }
            for (RouteOption option : options) {
                if (option.snapshotExpiresAt().isBefore(expiresAt)) {
                    throw new IllegalArgumentException("路线方案不得晚于完整快照失效");
                }
            }
        }

        /** 只发送路线方案摘要，路线折线及导航步骤留在 map-service。 */
        public List<RouteOptionSummary> summaries() {
            return options.stream().map(option -> new RouteOptionSummary(
                    option.routeOptionId(), option.via() == null ? null : option.via().place().name(),
                    option.via() == null ? null : option.via().businessType(),
                    option.durationSeconds(), option.isMapDefault(), expiresAt)).toList();
        }
    }

    public record SelectedRouteOption(String taskNo,
                                      RouteOption option,
                                      PassengerRouteSnapshot snapshot,
                                      Instant expiresAt) {
    }
}
