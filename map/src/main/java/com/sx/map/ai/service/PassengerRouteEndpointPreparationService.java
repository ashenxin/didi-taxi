package com.sx.map.ai.service;

import com.sx.map.ai.model.PassengerRouteSnapshot;
import com.sx.map.ai.model.PassengerRouteIntent;
import com.sx.map.ai.service.PassengerRouteEndpointResolver.CandidateSummary;
import com.sx.map.ai.service.PassengerRouteEndpointResolver.Resolution;
import com.sx.map.ai.service.PassengerRouteEndpointResolver.Status;
import com.sx.map.ai.service.PassengerRoutePendingConfirmationCache.PendingConfirmation;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 为客服首次复述起终点准备待确认的地图上下文。
 *
 * 调用方先由 passenger-service 为当前乘客消息取得活动请求和服务端 requestNo，
 * 再把从本次客服会话文字提取的两端名称交给本类。地图查询和 Redis 写入都在数据库事务外；
 * 只有返回 READY_FOR_CONFIRMATION 后，才可持久化与该 requestNo 对应的客服确认提问。
 * 调用方在持久化提问前还须复核活动请求与条件版本，防止迟到查询生成过时提问。
 * 这里不确认乘客意愿，也不调用驾车算路。
 */
@Service
public class PassengerRouteEndpointPreparationService {
    private static final int MAX_NUMBER_LENGTH = 64;
    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_REGION_LENGTH = 30;
    private static final int MAX_USER_TEXT_LENGTH = 1000;

    private final PassengerRouteEndpointResolver endpointResolver;
    private final PassengerRoutePendingConfirmationCache pendingCache;

    public PassengerRouteEndpointPreparationService(PassengerRouteEndpointResolver endpointResolver,
                                                    PassengerRoutePendingConfirmationCache pendingCache) {
        this.endpointResolver = endpointResolver;
        this.pendingCache = pendingCache;
    }

    /**
     * 两端都唯一且地图数据有效时才保存待确认记录。任一端需要澄清时，仅返回可读摘要，
     * 不缓存部分地点，不能据此生成路线或要求乘客对尚不明确的两端直接回复“对”。
     */
    public PreparationResult prepare(long customerId,
                                     String conversationNo,
                                     String confirmationRequestNo,
                                     long conditionVersion,
                                     String originName,
                                     String originRegion,
                                     String destinationName,
                                     String destinationRegion,
                                     PassengerRouteIntent intent,
                                     String originalUserText) {
        // 在任何可能计费的地图查询前检查完整输入，避免只查完起点才发现终点或归属无效。
        if (customerId <= 0 || conditionVersion <= 0) {
            throw new IllegalArgumentException("缺少可信乘客或条件版本");
        }
        String trustedConversationNo = requiredText(conversationNo, "会话编号", MAX_NUMBER_LENGTH);
        String trustedRequestNo = requiredText(confirmationRequestNo, "确认提问请求号", MAX_NUMBER_LENGTH);
        if (!trustedConversationNo.equals(conversationNo)
                || !trustedRequestNo.equals(confirmationRequestNo)) {
            throw new IllegalArgumentException("服务端会话编号或请求号格式无效");
        }
        String origin = requiredText(originName, "起点名称", MAX_NAME_LENGTH);
        String destination = requiredText(destinationName, "终点名称", MAX_NAME_LENGTH);
        String originArea = optionalText(originRegion, "起点地区", MAX_REGION_LENGTH);
        String destinationArea = optionalText(destinationRegion, "终点地区", MAX_REGION_LENGTH);
        Objects.requireNonNull(intent, "原始业务意图不能为空");
        String userText = requiredText(originalUserText, "原始提问", MAX_USER_TEXT_LENGTH);

        Resolution originResolution = endpointResolver.resolve(origin, originArea);
        Resolution destinationResolution = endpointResolver.resolve(destination, destinationArea);
        EndpointFeedback originFeedback = EndpointFeedback.from(originResolution);
        EndpointFeedback destinationFeedback = EndpointFeedback.from(destinationResolution);
        if (originResolution.status() != Status.UNIQUE
                || destinationResolution.status() != Status.UNIQUE) {
            return new PreparationResult(PreparationStatus.NEEDS_CLARIFICATION,
                    originFeedback, destinationFeedback, null);
        }

        PendingConfirmation pending = pendingCache.savePending(
                customerId, trustedConversationNo, trustedRequestNo, conditionVersion,
                originResolution.place(), destinationResolution.place(), intent, userText);
        return new PreparationResult(PreparationStatus.READY_FOR_CONFIRMATION,
                originFeedback, destinationFeedback, pending.expiresAt());
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
        READY_FOR_CONFIRMATION,
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
    }

    public record PreparationResult(PreparationStatus status,
                                    EndpointFeedback origin,
                                    EndpointFeedback destination,
                                    Instant expiresAt) {
        public PreparationResult {
            Objects.requireNonNull(status, "确认准备状态不能为空");
            Objects.requireNonNull(origin, "起点反馈不能为空");
            Objects.requireNonNull(destination, "终点反馈不能为空");
            if (status == PreparationStatus.READY_FOR_CONFIRMATION) {
                Objects.requireNonNull(expiresAt, "待确认记录失效时间不能为空");
                if (origin.status() != Status.UNIQUE || destination.status() != Status.UNIQUE) {
                    throw new IllegalArgumentException("未定位两端时不能等待乘客确认");
                }
            } else if (expiresAt != null) {
                throw new IllegalArgumentException("待澄清时不能返回确认有效期");
            }
        }
    }
}
