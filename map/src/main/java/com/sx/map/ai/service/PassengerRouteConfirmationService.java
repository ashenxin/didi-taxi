package com.sx.map.ai.service;

import com.sx.map.ai.service.PassengerRouteConditionsCache.ConfirmedConditions;
import com.sx.map.ai.service.PassengerRoutePendingConfirmationCache.PendingConfirmation;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * 将乘客对客服最新起终点提问的明确确认接续为已确认路线条件。
 *
 * 调用方必须从 passenger-service 已持久化的最新客服确认提问取得 requestNo，
 * 并在当前乘客消息的活动请求内调用；不能采用客户端、模型或任意历史消息提供的请求号。
 * 本类只处理独立的“对”。地点修正和首轮直接确认完整两端由其他分支重新核对地图地点。
 */
@Service
public class PassengerRouteConfirmationService {
    private final PassengerRoutePendingConfirmationCache pendingCache;
    private final PassengerRouteConditionsCache conditionsCache;

    public PassengerRouteConfirmationService(PassengerRoutePendingConfirmationCache pendingCache,
                                             PassengerRouteConditionsCache conditionsCache) {
        this.pendingCache = pendingCache;
        this.conditionsCache = conditionsCache;
    }

    /**
     * 只有独立的“对”且对应的待确认地点仍有效时才写入条件。成功后返回原始提问，
     * 供后续编排恢复原意图；原始文字不能反向覆盖这里已确认的地图地点。
     *
     * 写入 Redis 后，调用方仍须在持久化客服结果前复核活动请求与条件版本；
     * 若持久化失败，未引用的短时条件由 TTL 清理，不能把它当作已交付的路线。
     */
    public ConfirmationResult confirm(long customerId,
                                      String conversationNo,
                                      String latestConfirmationRequestNo,
                                      long conditionVersion,
                                      String replyText) {
        if (replyText == null || !"对".equals(replyText.strip())) {
            return ConfirmationResult.notAccepted();
        }

        Optional<PendingConfirmation> pending = pendingCache.find(
                customerId, conversationNo, latestConfirmationRequestNo, conditionVersion);
        if (pending.isEmpty()) {
            return ConfirmationResult.contextUnavailable();
        }

        PendingConfirmation candidate = pending.get();
        Optional<ConfirmedConditions> existing = conditionsCache.find(
                customerId, conversationNo, conditionVersion);
        if (existing.isPresent()) {
            ConfirmedConditions conditions = existing.get();
            if (!conditions.origin().equals(candidate.origin())
                    || !conditions.destination().equals(candidate.destination())) {
                return ConfirmationResult.contextUnavailable();
            }
            return ConfirmationResult.confirmed(conditions, candidate.originalUserText());
        }

        ConfirmedConditions conditions = conditionsCache.saveConfirmed(
                customerId, conversationNo, conditionVersion,
                candidate.origin(), candidate.destination());
        return ConfirmationResult.confirmed(conditions, candidate.originalUserText());
    }

    public enum Status {
        NOT_ACCEPTED,
        CONTEXT_UNAVAILABLE,
        CONFIRMED
    }

    /** 非确认回复交给地点修正分支；上下文失效时重新询问完整起终点。 */
    public record ConfirmationResult(Status status,
                                     ConfirmedConditions conditions,
                                     String originalUserText) {
        public ConfirmationResult {
            Objects.requireNonNull(status, "确认状态不能为空");
            if (status == Status.CONFIRMED) {
                Objects.requireNonNull(conditions, "已确认条件不能为空");
                if (originalUserText == null || originalUserText.isBlank()) {
                    throw new IllegalArgumentException("原始提问不能为空");
                }
            } else if (conditions != null || originalUserText != null) {
                throw new IllegalArgumentException("未确认时不能返回地点或原始提问");
            }
        }

        private static ConfirmationResult notAccepted() {
            return new ConfirmationResult(Status.NOT_ACCEPTED, null, null);
        }

        private static ConfirmationResult contextUnavailable() {
            return new ConfirmationResult(Status.CONTEXT_UNAVAILABLE, null, null);
        }

        private static ConfirmationResult confirmed(ConfirmedConditions conditions, String originalUserText) {
            return new ConfirmationResult(Status.CONFIRMED, conditions, originalUserText);
        }
    }
}
