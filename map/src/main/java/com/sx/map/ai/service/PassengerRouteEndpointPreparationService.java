package com.sx.map.ai.service;

import com.sx.map.ai.model.PassengerRouteSnapshot;
import com.sx.map.ai.model.PassengerRouteIntent;
import com.sx.map.ai.service.PassengerRouteEndpointResolver.CandidateSummary;
import com.sx.map.ai.service.PassengerRouteEndpointResolver.Resolution;
import com.sx.map.ai.service.PassengerRouteEndpointResolver.Status;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.ChoiceRole;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.ChoiceSummary;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.ChoiceTask;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.PlaceCandidate;
import com.sx.map.ai.service.PassengerRoutePlaceChoiceCache.SelectedChoice;
import com.sx.map.ai.service.PassengerRouteConditionsCache.ConfirmedConditions;
import com.sx.map.ai.service.PassengerRoutePartialEndpointCache.EndpointRole;
import com.sx.map.ai.service.PassengerRoutePartialEndpointCache.PartialEndpoint;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 为客服路线请求解析并确认可信起终点。
 *
 * 调用方先由 passenger-service 为当前乘客消息取得活动请求和服务端 requestNo，
 * 再把从本次客服会话文字提取的两端名称交给本类。地图查询和 Redis 写入都在数据库事务外；
 * 两端地图身份都唯一，或乘客已经完成候选选择后，直接写入当前路线条件并返回 READY_FOR_ROUTE。
 * 返回 NEEDS_PLACE_CHOICE 时，调用方应先持久化地点选项摘要，再允许乘客选择。
 * 调用方在持久化结果前还须复核活动请求与条件版本，防止迟到查询生成过时结果。
 * 本类不调用驾车算路；调用方取得 READY_FOR_ROUTE 后应立即继续原路线意图。
 */
@Service
public class PassengerRouteEndpointPreparationService {
    private static final int MAX_NUMBER_LENGTH = 64;
    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_REGION_LENGTH = 30;
    private static final int MAX_USER_TEXT_LENGTH = 1000;

    private final PassengerRouteEndpointResolver endpointResolver;
    private final PassengerRouteConditionsCache conditionsCache;
    private final PassengerRoutePlaceChoiceCache placeChoiceCache;
    private final PassengerRoutePartialEndpointCache partialEndpointCache;

    public PassengerRouteEndpointPreparationService(PassengerRouteEndpointResolver endpointResolver,
                                                    PassengerRouteConditionsCache conditionsCache,
                                                    PassengerRoutePlaceChoiceCache placeChoiceCache,
                                                    PassengerRoutePartialEndpointCache partialEndpointCache) {
        this.endpointResolver = endpointResolver;
        this.conditionsCache = conditionsCache;
        this.placeChoiceCache = placeChoiceCache;
        this.partialEndpointCache = partialEndpointCache;
    }

    /**
     * 首轮只提供一个端点时先确认地点身份。唯一地点写入短时缓存，重名地点仍由乘客选择；
     * 两种情况都不会在另一端缺失时发起算路。
     */
    public PreparationResult preparePartial(long customerId, String conversationNo,
                                            String requestNo, long conditionVersion,
                                            EndpointRole endpointRole, String placeName,
                                            String placeRegion, PassengerRouteIntent intent,
                                            String originalUserText) {
        validateOwnerAndVersion(customerId, conversationNo, requestNo, conditionVersion);
        Objects.requireNonNull(endpointRole, "单端点角色不能为空");
        Objects.requireNonNull(intent, "原始业务意图不能为空");
        String name = requiredText(placeName, "地点名称", MAX_NAME_LENGTH);
        String region = optionalText(placeRegion, "地点地区", MAX_REGION_LENGTH);
        String userText = requiredText(originalUserText, "原始提问", MAX_USER_TEXT_LENGTH);
        Resolution resolution = endpointResolver.resolve(name, region, endpointRole);
        EndpointFeedback knownFeedback = EndpointFeedback.from(resolution);
        EndpointFeedback missingFeedback = EndpointFeedback.missing();
        EndpointFeedback originFeedback = endpointRole == EndpointRole.ORIGIN
                ? knownFeedback : missingFeedback;
        EndpointFeedback destinationFeedback = endpointRole == EndpointRole.DESTINATION
                ? knownFeedback : missingFeedback;
        if (resolution.status() == Status.UNIQUE) {
            PartialEndpoint partial = partialEndpointCache.save(customerId, conversationNo,
                    requestNo, conditionVersion, endpointRole, resolution.place(), intent, userText);
            return PreparationResult.waiting(originFeedback, destinationFeedback, partial);
        }
        if (resolution.selectableCandidates().isEmpty()) {
            return PreparationResult.clarification(originFeedback, destinationFeedback);
        }
        ChoiceRole choiceRole = endpointRole == EndpointRole.ORIGIN
                ? ChoiceRole.ORIGIN : ChoiceRole.DESTINATION;
        List<PlaceCandidate> candidates = toPlaceCandidates(resolution);
        ChoiceTask task = placeChoiceCache.save(customerId, conversationNo,
                conditionVersion, conditionVersion, choiceRole, intent, userText,
                null, null, candidates);
        return PreparationResult.placeChoices(originFeedback, destinationFeedback,
                choiceRole, task.taskNo(), task.summaries(), task.expiresAt());
    }

    /** 使用上一条服务端追问绑定的可信单端点，解析本轮补充的另一端。 */
    public PreparationResult completePartial(long customerId, String conversationNo,
                                             String followUpRequestNo, long partialConditionVersion,
                                             String currentRequestNo, long currentConditionVersion,
                                             EndpointRole endpointRole, String placeName,
                                             String placeRegion) {
        validateOwnerAndVersion(customerId, conversationNo, currentRequestNo, currentConditionVersion);
        String trustedFollowUpRequestNo = requiredText(
                followUpRequestNo, "待续接请求号", MAX_NUMBER_LENGTH);
        PartialEndpoint partial = partialEndpointCache.find(customerId, conversationNo,
                trustedFollowUpRequestNo, partialConditionVersion).orElse(null);
        if (partial == null || endpointRole == null || endpointRole == partial.knownRole()) {
            return null;
        }
        String name = requiredText(placeName, "待补齐地点名称", MAX_NAME_LENGTH);
        String region = optionalText(placeRegion, "待补齐地点地区", MAX_REGION_LENGTH);
        Resolution resolution = endpointResolver.resolve(name, region, endpointRole);
        EndpointFeedback knownFeedback = uniqueFeedback(partial.knownPlace());
        EndpointFeedback missingFeedback = EndpointFeedback.from(resolution);
        EndpointFeedback originFeedback = partial.knownRole() == EndpointRole.ORIGIN
                ? knownFeedback : missingFeedback;
        EndpointFeedback destinationFeedback = partial.knownRole() == EndpointRole.DESTINATION
                ? knownFeedback : missingFeedback;
        PassengerRouteSnapshot.Place origin = partial.knownRole() == EndpointRole.ORIGIN
                ? partial.knownPlace() : resolution.place();
        PassengerRouteSnapshot.Place destination = partial.knownRole() == EndpointRole.DESTINATION
                ? partial.knownPlace() : resolution.place();
        if (resolution.status() != Status.UNIQUE) {
            if (resolution.selectableCandidates().isEmpty()) {
                return PreparationResult.clarification(originFeedback, destinationFeedback);
            }
            ChoiceRole choiceRole = endpointRole == EndpointRole.ORIGIN
                    ? ChoiceRole.ORIGIN : ChoiceRole.DESTINATION;
            ChoiceTask task = placeChoiceCache.save(customerId, conversationNo,
                    currentConditionVersion, currentConditionVersion, choiceRole,
                    partial.intent(), partial.originalUserText(),
                    partial.knownRole() == EndpointRole.ORIGIN ? partial.knownPlace() : null,
                    partial.knownRole() == EndpointRole.DESTINATION ? partial.knownPlace() : null,
                    toPlaceCandidates(resolution));
            return PreparationResult.placeChoices(originFeedback, destinationFeedback,
                    choiceRole, task.taskNo(), task.summaries(), task.expiresAt());
        }
        ConfirmedConditions confirmed = conditionsCache.saveConfirmed(customerId, conversationNo,
                currentConditionVersion, origin, destination);
        return PreparationResult.ready(originFeedback, destinationFeedback, confirmed.expiresAt());
    }

    /**
     * 两端都唯一且地图数据有效时直接保存路线条件。端点有多个可选地点时，
     * 将其中一个端点的可信候选写入短时选项；若两端都不唯一，先澄清起点。
     * 地图数据无效或没有可选候选时只返回可读反馈。任何待选状态都不能算路，
     * 乘客只需要完成地点选择，不再对已经选好的两端进行第二次文字确认。
     */
    public PreparationResult prepare(long customerId,
                                     String conversationNo,
                                     String requestNo,
                                     long conditionVersion,
                                     String originName,
                                     String originRegion,
                                     String destinationName,
                                     String destinationRegion,
                                     PassengerRouteIntent intent,
                                     String originalUserText) {
        // 在任何可能计费的地图查询前检查完整输入，避免只查完起点才发现终点或归属无效。
        validateOwnerAndVersion(customerId, conversationNo, requestNo, conditionVersion);
        String trustedConversationNo = conversationNo;
        String origin = requiredText(originName, "起点名称", MAX_NAME_LENGTH);
        String destination = requiredText(destinationName, "终点名称", MAX_NAME_LENGTH);
        String originArea = optionalText(originRegion, "起点地区", MAX_REGION_LENGTH);
        String destinationArea = optionalText(destinationRegion, "终点地区", MAX_REGION_LENGTH);
        Objects.requireNonNull(intent, "原始业务意图不能为空");
        String userText = requiredText(originalUserText, "原始提问", MAX_USER_TEXT_LENGTH);

        Resolution originResolution = endpointResolver.resolve(origin, originArea, EndpointRole.ORIGIN);
        Resolution destinationResolution = endpointResolver.resolve(destination, destinationArea, EndpointRole.DESTINATION);
        EndpointFeedback originFeedback = EndpointFeedback.from(originResolution);
        EndpointFeedback destinationFeedback = EndpointFeedback.from(destinationResolution);
        if (originResolution.status() != Status.UNIQUE) {
            if (originResolution.selectableCandidates().isEmpty()) {
                return PreparationResult.clarification(originFeedback, destinationFeedback);
            }
            return saveChoices(customerId, trustedConversationNo, conditionVersion,
                    intent, userText, ChoiceRole.ORIGIN, originResolution, destinationResolution,
                    originFeedback, destinationFeedback, destination, destinationArea);
        }
        if (destinationResolution.status() != Status.UNIQUE) {
            if (destinationResolution.selectableCandidates().isEmpty()) {
                return PreparationResult.clarification(originFeedback, destinationFeedback);
            }
            return saveChoices(customerId, trustedConversationNo, conditionVersion,
                    intent, userText, ChoiceRole.DESTINATION, originResolution, destinationResolution,
                    originFeedback, destinationFeedback, null, null);
        }

        ConfirmedConditions confirmed = conditionsCache.saveConfirmed(
                customerId, trustedConversationNo, conditionVersion,
                originResolution.place(), destinationResolution.place());
        return PreparationResult.ready(originFeedback, destinationFeedback, confirmed.expiresAt());
    }

    private PreparationResult saveChoices(long customerId, String conversationNo, long conditionVersion,
                                          PassengerRouteIntent intent, String userText, ChoiceRole role,
                                          Resolution originResolution, Resolution destinationResolution,
                                          EndpointFeedback originFeedback, EndpointFeedback destinationFeedback,
                                          String remainingName, String remainingRegion) {
        Resolution unresolved = role == ChoiceRole.ORIGIN ? originResolution : destinationResolution;
        List<PlaceCandidate> candidates = toPlaceCandidates(unresolved);
        // 首轮 prepare 的条件版本取自 begin.requestVersion，因此这里同时是选项所属的
        // 请求版本。完整地点留在 map-service；返回给编排层的只有 taskNo 和展示摘要。
        ChoiceTask task = role == ChoiceRole.ORIGIN && destinationResolution.place() == null
                ? placeChoiceCache.save(customerId, conversationNo, conditionVersion, conditionVersion,
                        role, intent, userText, null, null, remainingName, remainingRegion, candidates)
                : placeChoiceCache.save(customerId, conversationNo, conditionVersion, conditionVersion,
                        role, intent, userText, originResolution.place(), destinationResolution.place(), candidates);
        return PreparationResult.placeChoices(originFeedback, destinationFeedback,
                role, task.taskNo(), task.summaries(), task.expiresAt());
    }

    /** 选择只读取 map-service 保存的地点身份；若两端都歧义，继续让乘客选择终点。 */
    public PreparationResult select(long customerId, String conversationNo, String taskNo,
                                    String placeChoiceId, long conditionVersion, long choiceRequestVersion,
                                    long currentRequestVersion, String currentRequestNo) {
        SelectedChoice selected = placeChoiceCache.select(taskNo, placeChoiceId, customerId,
                conversationNo, conditionVersion, choiceRequestVersion).orElse(null);
        if (selected != null && selected.role() != ChoiceRole.WAYPOINT
                && !PassengerRouteEndpointResolver.directionCompatible(selected.candidate().place().name(),
                selected.candidate().displayType(), selected.role() == ChoiceRole.ORIGIN
                        ? EndpointRole.ORIGIN : EndpointRole.DESTINATION)) {
            return null; // 单端点选择也不能恢复旧缓存中的反向候选。
        }
        if (selected == null || selected.role() == ChoiceRole.WAYPOINT || currentRequestVersion <= choiceRequestVersion
                || (selected.role() == ChoiceRole.DESTINATION && selected.origin() == null)) {
            if (selected == null || selected.role() == ChoiceRole.WAYPOINT
                    || currentRequestVersion <= choiceRequestVersion) {
                return null;
            }
            // 首轮单端点的重名地点选择完成后，只保存该端点并继续追问另一端。
            if (selected.role() == ChoiceRole.DESTINATION && selected.origin() == null) {
                return saveSelectedPartial(customerId, conversationNo, currentRequestNo,
                        currentRequestVersion, selected);
            }
            return null;
        }
        if (selected.role() == ChoiceRole.ORIGIN && selected.destination() == null
                && selected.remainingName() == null) {
            return saveSelectedPartial(customerId, conversationNo, currentRequestNo,
                    currentRequestVersion, selected);
        }
        PassengerRouteSnapshot.Place origin = selected.role() == ChoiceRole.ORIGIN
                ? selected.candidate().place() : selected.origin();
        PassengerRouteSnapshot.Place destination = selected.role() == ChoiceRole.DESTINATION
                ? selected.candidate().place() : selected.destination();
        EndpointFeedback originFeedback = uniqueFeedback(origin);
        EndpointFeedback destinationFeedback;
        if (destination == null) {
            Resolution resolution = endpointResolver.resolve(selected.remainingName(), selected.remainingRegion(),
                    EndpointRole.DESTINATION);
            destinationFeedback = EndpointFeedback.from(resolution);
            if (resolution.status() != Status.UNIQUE) {
                if (resolution.selectableCandidates().isEmpty()) {
                    return PreparationResult.clarification(originFeedback, destinationFeedback);
                }
                List<PlaceCandidate> candidates = resolution.selectableCandidates().stream()
                        .map(candidate -> new PlaceCandidate(candidate.place(), null, List.of(),
                                candidate.summary().type())).toList();
                ChoiceTask next = placeChoiceCache.save(customerId, conversationNo, conditionVersion,
                        currentRequestVersion, ChoiceRole.DESTINATION, selected.intent(),
                        selected.originalUserText(), origin, null, candidates);
                return PreparationResult.placeChoices(originFeedback, destinationFeedback,
                        ChoiceRole.DESTINATION, next.taskNo(), next.summaries(), next.expiresAt());
            }
            destination = resolution.place();
        } else {
            destinationFeedback = uniqueFeedback(destination);
        }
        ConfirmedConditions confirmed = conditionsCache.saveConfirmed(
                customerId, conversationNo, conditionVersion, origin, destination);
        return PreparationResult.ready(originFeedback, destinationFeedback, confirmed.expiresAt());
    }

    /** 保留旧调用签名，现有完整起终点选项流程不会走单端点分支。 */
    public PreparationResult select(long customerId, String conversationNo, String taskNo,
                                    String placeChoiceId, long conditionVersion,
                                    long choiceRequestVersion, long currentRequestVersion) {
        return select(customerId, conversationNo, taskNo, placeChoiceId, conditionVersion,
                choiceRequestVersion, currentRequestVersion, null);
    }

    private PreparationResult saveSelectedPartial(long customerId, String conversationNo,
                                                   String currentRequestNo,
                                                   long currentRequestVersion,
                                                   SelectedChoice selected) {
        String trustedRequestNo = requiredText(currentRequestNo, "当前请求号", MAX_NUMBER_LENGTH);
        EndpointRole role = selected.role() == ChoiceRole.ORIGIN
                ? EndpointRole.ORIGIN : EndpointRole.DESTINATION;
        PartialEndpoint partial = partialEndpointCache.save(customerId, conversationNo,
                trustedRequestNo, currentRequestVersion, role, selected.candidate().place(),
                selected.intent(), selected.originalUserText());
        EndpointFeedback known = uniqueFeedback(selected.candidate().place());
        EndpointFeedback missing = EndpointFeedback.missing();
        return PreparationResult.waiting(
                role == EndpointRole.ORIGIN ? known : missing,
                role == EndpointRole.DESTINATION ? known : missing,
                partial);
    }

    private static List<PlaceCandidate> toPlaceCandidates(Resolution resolution) {
        return resolution.selectableCandidates().stream()
                .map(candidate -> new PlaceCandidate(candidate.place(), null, List.of(),
                        candidate.summary().type()))
                .toList();
    }

    private static void validateOwnerAndVersion(long customerId, String conversationNo,
                                                String requestNo, long conditionVersion) {
        if (customerId <= 0 || conditionVersion <= 0) {
            throw new IllegalArgumentException("缺少可信乘客或条件版本");
        }
        String trustedConversationNo = requiredText(conversationNo, "会话编号", MAX_NUMBER_LENGTH);
        String trustedRequestNo = requiredText(requestNo, "当前请求号", MAX_NUMBER_LENGTH);
        if (!trustedConversationNo.equals(conversationNo) || !trustedRequestNo.equals(requestNo)) {
            throw new IllegalArgumentException("服务端会话编号或请求号格式无效");
        }
    }

    private static EndpointFeedback uniqueFeedback(PassengerRouteSnapshot.Place place) {
        return new EndpointFeedback(Status.UNIQUE, List.of(new CandidateSummary(
                place.name(), null, place.city(), null, place.address())));
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + "超过长度限制");
        }
        return normalized;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + "超过长度限制");
        }
        return normalized;
    }

    public enum PreparationStatus {
        READY_FOR_ROUTE,
        WAITING_FOR_OTHER_ENDPOINT,
        NEEDS_PLACE_CHOICE,
        NEEDS_CLARIFICATION
    }

    /**
     * 给客服组织复述或澄清话术的可读信息；不包含地图 ID 或坐标。
     * UNIQUE 只代表地图身份唯一，乘客尚未确认使用该端点。
     */
    public record EndpointFeedback(Status status, List<CandidateSummary> candidates) {
        public EndpointFeedback {
            Objects.requireNonNull(status, "端点解析状态不能为空");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "地点摘要不能为空"));
        }

        private static EndpointFeedback from(Resolution resolution) {
            if (resolution.status() != Status.UNIQUE) {
                return new EndpointFeedback(resolution.status(), resolution.candidates());
            }
            PassengerRouteSnapshot.Place place = resolution.place();
            return new EndpointFeedback(Status.UNIQUE, List.of(new CandidateSummary(
                    place.name(), null, place.city(), null, place.address())));
        }

        private static EndpointFeedback missing() {
            return new EndpointFeedback(Status.MISSING, List.of());
        }
    }

    public record PartialEndpointSummary(long conditionVersion, String followUpRequestNo,
                                         EndpointRole knownRole, String knownPlaceName,
                                         PassengerRouteIntent intent, Instant expiresAt) {
        private static PartialEndpointSummary from(PartialEndpoint partial) {
            return new PartialEndpointSummary(partial.conditionVersion(), partial.followUpRequestNo(),
                    partial.knownRole(), partial.knownPlace().name(), partial.intent(), partial.expiresAt());
        }
    }

    public record PreparationResult(PreparationStatus status,
                                    EndpointFeedback origin,
                                    EndpointFeedback destination,
                                    ChoiceRole choiceRole,
                                    String choiceTaskNo,
                                    List<ChoiceSummary> choices,
                                    Instant expiresAt,
                                    PartialEndpointSummary partialEndpoint) {
        public PreparationResult {
            Objects.requireNonNull(status, "确认准备状态不能为空");
            Objects.requireNonNull(origin, "起点反馈不能为空");
            Objects.requireNonNull(destination, "终点反馈不能为空");
            choices = List.copyOf(Objects.requireNonNull(choices, "地点选项摘要不能为空"));
            if (status == PreparationStatus.READY_FOR_ROUTE) {
                Objects.requireNonNull(expiresAt, "已确认路线条件失效时间不能为空");
                if (origin.status() != Status.UNIQUE || destination.status() != Status.UNIQUE) {
                    throw new IllegalArgumentException("未定位两端时不能继续路线处理");
                }
                if (choiceRole != null || choiceTaskNo != null || !choices.isEmpty()) {
                    throw new IllegalArgumentException("已确认路线条件不能附带地点选项");
                }
                if (partialEndpoint != null) {
                    throw new IllegalArgumentException("已确认路线条件不能附带单端点");
                }
            } else if (status == PreparationStatus.WAITING_FOR_OTHER_ENDPOINT) {
                Objects.requireNonNull(partialEndpoint, "单端点续接信息不能为空");
                Objects.requireNonNull(expiresAt, "单端点续接有效期不能为空");
                if (choiceRole != null || choiceTaskNo != null || !choices.isEmpty()) {
                    throw new IllegalArgumentException("单端点续接不能附带地点选项");
                }
            } else if (status == PreparationStatus.NEEDS_PLACE_CHOICE) {
                Objects.requireNonNull(choiceRole, "待选择地点缺少角色");
                if (choiceTaskNo == null || choiceTaskNo.isBlank() || choices.isEmpty()) {
                    throw new IllegalArgumentException("待选择地点缺少任务或选项");
                }
                Objects.requireNonNull(expiresAt, "待选择地点缺少有效期");
                if (choiceRole == ChoiceRole.ORIGIN && origin.status() == Status.UNIQUE
                        || choiceRole == ChoiceRole.DESTINATION && destination.status() == Status.UNIQUE) {
                    throw new IllegalArgumentException("唯一地点不应再次要求选择");
                }
                if (partialEndpoint != null) {
                    throw new IllegalArgumentException("地点选择不能附带单端点续接信息");
                }
            } else if (choiceRole != null || choiceTaskNo != null || !choices.isEmpty()
                    || expiresAt != null || partialEndpoint != null) {
                throw new IllegalArgumentException("仅文字澄清不能附带地点选项或有效期");
            }
        }

        private static PreparationResult ready(EndpointFeedback origin, EndpointFeedback destination,
                                               Instant expiresAt) {
            return new PreparationResult(PreparationStatus.READY_FOR_ROUTE,
                    origin, destination, null, null, List.of(), expiresAt, null);
        }

        private static PreparationResult waiting(EndpointFeedback origin, EndpointFeedback destination,
                                                 PartialEndpoint partial) {
            return new PreparationResult(PreparationStatus.WAITING_FOR_OTHER_ENDPOINT,
                    origin, destination, null, null, List.of(), partial.expiresAt(),
                    PartialEndpointSummary.from(partial));
        }

        private static PreparationResult placeChoices(EndpointFeedback origin, EndpointFeedback destination,
                                                      ChoiceRole role, String taskNo,
                                                      List<ChoiceSummary> choices, Instant expiresAt) {
            return new PreparationResult(PreparationStatus.NEEDS_PLACE_CHOICE,
                    origin, destination, role, taskNo, choices, expiresAt, null);
        }

        private static PreparationResult clarification(EndpointFeedback origin, EndpointFeedback destination) {
            return new PreparationResult(PreparationStatus.NEEDS_CLARIFICATION,
                    origin, destination, null, null, List.of(), null, null);
        }
    }
}
