package com.sx.passenger.ai.service;

import com.sx.passenger.ai.AiIdentifierGenerator;
import com.sx.passenger.ai.dao.PassengerAiConversationMapper;
import com.sx.passenger.ai.dao.PassengerAiMessageMapper;
import com.sx.passenger.ai.dto.AiConversationCreateResponse;
import com.sx.passenger.ai.dto.AiMessageListResponse;
import com.sx.passenger.ai.dto.AiTurnBeginResponse;
import com.sx.passenger.ai.dto.AiTurnCompleteResponse;
import com.sx.passenger.ai.dto.AiTurnFailResponse;
import com.sx.passenger.ai.dto.AssistantMessageView;
import com.sx.passenger.ai.entity.PassengerAiConversation;
import com.sx.passenger.ai.entity.PassengerAiMessage;
import com.sx.passenger.ai.exception.AiConversationConflictException;
import com.sx.passenger.ai.exception.AiConversationNotFoundException;
import com.sx.passenger.time.PassengerPersistenceTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * AI 客服会话与轮次消息的核心服务。
 *
 * 全部写路径使用两段短事务：begin 在会话锁内判定幂等键与过期接管，完成与失败回写在会话锁内复查，
 * 加锁占用活动请求、分配序号、插入消息在一个短事务内完成并立即提交，
 * 不在事务内等待模型或地图等外部调用。begin/complete/fail 均支持幂等重放，
 * 保证 BFF 网络重试不会造成重复消息或"结果已出却报失败"。
 */
@Service
public class AiConversationService {

    static final String ROLE_USER = "USER";
    static final String ROLE_ASSISTANT = "ASSISTANT";
    static final String TYPE_TEXT = "TEXT";
    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_COMPLETED = "COMPLETED";
    static final String STATUS_FAILED = "FAILED";
    static final String SCENE_ROUTE_WAYPOINT = "ROUTE_WAYPOINT";
    static final String STALE_REQUEST_CODE = AiConversationConflictException.AI_REQUEST_EXPIRED;

    /** fail 落库的面向乘客说明，不向乘客暴露内部失败细节。 */
    private static final String FAIL_CONTENT = "抱歉，服务暂时不可用，请稍后重试。";
    private static final String STALE_REQUEST_MESSAGE = "本轮处理已超时，请重新发送消息";

    /** 历史分页默认与上限（文档约定分页大小上限 50）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final PassengerAiConversationMapper conversations;
    private final PassengerAiMessageMapper messages;
    private final AiIdentifierGenerator identifiers;
    private final TransactionTemplate databaseTransaction;
    private final long staleTurnSeconds;

    public AiConversationService(PassengerAiConversationMapper conversations,
                                 PassengerAiMessageMapper messages,
                                 AiIdentifierGenerator identifiers,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${passenger.ai.turn-stale-seconds:60}") long staleTurnSeconds) {
        this.conversations = conversations;
        this.messages = messages;
        this.identifiers = identifiers;
        this.databaseTransaction = new TransactionTemplate(transactionManager);
        this.databaseTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.staleTurnSeconds = staleTurnSeconds;
    }

    /** 创建会话；同一乘客相同幂等键重放返回原会话。 */
    public AiConversationCreateResponse create(long customerId, String idempotencyKey) {
        PassengerAiConversation existing = conversations.findByCreateIdempotency(customerId, idempotencyKey);
        if (existing != null) {
            return new AiConversationCreateResponse(existing.getConversationNo());
        }
        LocalDateTime now = PassengerPersistenceTime.now();
        PassengerAiConversation conversation = new PassengerAiConversation()
                .setConversationNo(identifiers.nextConversationNo())
                .setCustomerId(customerId)
                .setCreateIdempotencyKey(idempotencyKey)
                .setSceneCode(SCENE_ROUTE_WAYPOINT)
                .setStatus(STATUS_ACTIVE)
                .setLastMessageSequence(0L)
                .setSummaryThroughSequence(0L)
                .setRowVersion(0L)
                .setLastMessageAt(now)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        try {
            return databaseTransaction.execute(status -> {
                conversations.insert(conversation);
                return new AiConversationCreateResponse(conversation.getConversationNo());
            });
        } catch (DuplicateKeyException e) {
            // 并发重复创建：唯一索引兜底后回查重放。
            PassengerAiConversation winner = conversations.findByCreateIdempotency(customerId, idempotencyKey);
            if (winner == null) {
                throw e;
            }
            return new AiConversationCreateResponse(winner.getConversationNo());
        }
    }

    /** 开始一轮乘客消息：保存消息、占用活动请求、返回请求号与重放信息。 */
    public AiTurnBeginResponse begin(String conversationNo, long customerId, String idempotencyKey, String content) {
        return databaseTransaction.execute(status -> beginInTransaction(conversationNo, customerId,
                idempotencyKey, content));
    }

    private AiTurnBeginResponse beginInTransaction(String conversationNo, long customerId,
                                                   String idempotencyKey, String content) {
        PassengerAiConversation conversation = requireConversationForUpdate(conversationNo, customerId);
        requireAvailable(conversation);

        // 同键查重必须在会话行锁内；事务外查空后等待锁的请求也要看到已提交的消息。
        PassengerAiMessage replayed = messages.findUserByClientMessageNo(conversation.getId(), idempotencyKey);
        if (replayed != null) {
            if (!replayed.getContent().equals(content)) {
                throw new AiConversationConflictException(
                        AiConversationConflictException.AI_IDEMPOTENCY_CONFLICT,
                        "相同幂等键的内容不一致");
            }
            if (messages.findAssistantByRequestNo(conversation.getId(), replayed.getRequestNo()) != null) {
                return replayResponse(conversation, replayed);
            }
            if (replayed.getRequestNo().equals(conversation.getActiveRequestNo())) {
                if (!isStale(conversation)) {
                    throw new AiConversationConflictException(
                            AiConversationConflictException.AI_REQUEST_PROCESSING,
                            "相同幂等请求仍执行中");
                }
            }
            // 同键过期或旧版本留下的孤儿请求均收束为已落库失败，不能再运行第二次模型/地图调用。
            conversation = expireUnfinishedRequest(conversationNo, customerId, conversation, replayed.getRequestNo());
            return replayResponse(conversation, replayed);
        }

        String activeRequestNo = conversation.getActiveRequestNo();
        if (activeRequestNo != null) {
            if (isStale(conversation)) {
                // 接管前先给失联轮次写失败消息；迟到结果随后不能覆盖它。
                conversation = expireUnfinishedRequest(conversationNo, customerId, conversation, activeRequestNo);
            } else {
                throw new AiConversationConflictException(
                        AiConversationConflictException.AI_REQUEST_IN_PROGRESS,
                        "同会话已有其他请求进行中");
            }
        }

        long sequence = conversation.getLastMessageSequence() + 1;
        String requestNo = identifiers.nextRequestNo();
        LocalDateTime now = PassengerPersistenceTime.now();
        PassengerAiMessage userMessage = new PassengerAiMessage()
                .setMessageNo(identifiers.nextMessageNo())
                .setConversationId(conversation.getId())
                .setSequenceNo(sequence)
                .setRequestNo(requestNo)
                .setClientMessageNo(idempotencyKey)
                .setRole(ROLE_USER)
                .setMessageType(TYPE_TEXT)
                .setContent(content)
                .setStatus(STATUS_COMPLETED)
                .setCompletedAt(now)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        messages.insert(userMessage);

        int advanced = conversations.advanceTurn(conversation.getId(), conversation.getRowVersion(),
                requestNo, sequence, now, now);
        if (advanced != 1) {
            throw new AiConversationConflictException(
                    AiConversationConflictException.AI_REQUEST_IN_PROGRESS,
                    "会话状态已变化，请重试");
        }
        return new AiTurnBeginResponse(requestNo, sequence,
                assistantView(messages.findLatestByRole(conversation.getId(), ROLE_ASSISTANT)), null);
    }

    private PassengerAiConversation expireUnfinishedRequest(String conversationNo, long customerId,
                                                            PassengerAiConversation conversation, String requestNo) {
        PassengerAiMessage userMessage = messages.findUserByRequestNo(conversation.getId(), requestNo);
        if (userMessage == null) {
            throw new AiConversationConflictException(
                    AiConversationConflictException.AI_REQUEST_IN_PROGRESS,
                    "失联请求缺少用户消息");
        }
        long sequence = conversation.getLastMessageSequence() + 1;
        LocalDateTime now = PassengerPersistenceTime.now();
        PassengerAiMessage failedMessage = new PassengerAiMessage()
                .setMessageNo(identifiers.nextMessageNo())
                .setConversationId(conversation.getId())
                .setSequenceNo(sequence)
                .setRequestNo(requestNo)
                .setReplyToMessageId(userMessage.getId())
                .setRole(ROLE_ASSISTANT)
                .setMessageType(TYPE_TEXT)
                .setContent(STALE_REQUEST_MESSAGE)
                .setStatus(STATUS_FAILED)
                .setFailureCode(STALE_REQUEST_CODE)
                .setFailureMessage(STALE_REQUEST_MESSAGE)
                .setCompletedAt(now)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        messages.insert(failedMessage);

        int advanced = requestNo.equals(conversation.getActiveRequestNo())
                ? conversations.releaseActiveRequest(conversation.getId(), requestNo, sequence, now)
                : conversations.advanceMessageSequence(conversation.getId(), conversation.getRowVersion(), sequence, now);
        if (advanced != 1) {
            throw new AiConversationConflictException(
                    AiConversationConflictException.AI_REQUEST_IN_PROGRESS,
                    "失联请求收束失败");
        }
        return requireConversationForUpdate(conversationNo, customerId);
    }

    /** 完成一轮客服消息：保存回复、释放活动请求；重试重放原结果。 */
    public AiTurnCompleteResponse complete(String conversationNo, String requestNo, long customerId,
                                           String messageType, String content, String payloadJson) {
        PassengerAiConversation snapshot = requireConversation(conversationNo, customerId);
        PassengerAiMessage replayed = messages.findAssistantByRequestNo(snapshot.getId(), requestNo);
        if (replayed != null) {
            return completeView(replayed);
        }
        return databaseTransaction.execute(status -> {
            PassengerAiConversation conversation = requireConversationForUpdate(conversationNo, customerId);
            requireAvailable(conversation);
            // 等待会话行锁期间，另一请求可能已完成并释放活动请求；锁内再次查询才能正确重放。
            PassengerAiMessage committedReply = messages.findAssistantByRequestNo(conversation.getId(), requestNo);
            if (committedReply != null) {
                return completeView(committedReply);
            }
            if (!requestNo.equals(conversation.getActiveRequestNo())) {
                throw new AiConversationConflictException(
                        AiConversationConflictException.AI_REQUEST_IN_PROGRESS,
                        "请求不是当前活动请求");
            }
            PassengerAiMessage userMessage = messages.findUserByRequestNo(conversation.getId(), requestNo);
            if (userMessage == null) {
                throw new AiConversationConflictException(
                        AiConversationConflictException.AI_REQUEST_IN_PROGRESS,
                        "活动请求缺少用户消息");
            }
            long sequence = conversation.getLastMessageSequence() + 1;
            LocalDateTime now = PassengerPersistenceTime.now();
            PassengerAiMessage assistantMessage = new PassengerAiMessage()
                    .setMessageNo(identifiers.nextMessageNo())
                    .setConversationId(conversation.getId())
                    .setSequenceNo(sequence)
                    .setRequestNo(requestNo)
                    .setReplyToMessageId(userMessage.getId())
                    .setRole(ROLE_ASSISTANT)
                    .setMessageType(messageType)
                    .setContent(content)
                    .setPayloadJson(payloadJson)
                    .setStatus(STATUS_COMPLETED)
                    .setCompletedAt(now)
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            messages.insert(assistantMessage);

            int released = conversations.releaseActiveRequest(conversation.getId(), requestNo, sequence, now);
            if (released != 1) {
                throw new AiConversationConflictException(
                        AiConversationConflictException.AI_REQUEST_IN_PROGRESS,
                        "活动请求释放失败");
            }
            return completeView(assistantMessage);
        });
    }

    /** 标记一轮失败：保存失败说明、释放活动请求；重试重放原失败结果。 */
    public AiTurnFailResponse fail(String conversationNo, String requestNo, long customerId,
                                   String failureCode, String failureMessage) {
        PassengerAiConversation snapshot = requireConversation(conversationNo, customerId);
        PassengerAiMessage replayed = messages.findAssistantByRequestNo(snapshot.getId(), requestNo);
        if (replayed != null) {
            return new AiTurnFailResponse(replayed.getMessageNo(), requestNo, replayed.getSequenceNo());
        }
        return databaseTransaction.execute(status -> {
            PassengerAiConversation conversation = requireConversationForUpdate(conversationNo, customerId);
            requireAvailable(conversation);
            // 与 complete 使用相同的锁内重放检查，避免并发失败回写被误判为活动请求冲突。
            PassengerAiMessage committedReply = messages.findAssistantByRequestNo(conversation.getId(), requestNo);
            if (committedReply != null) {
                return new AiTurnFailResponse(committedReply.getMessageNo(), requestNo,
                        committedReply.getSequenceNo());
            }
            if (!requestNo.equals(conversation.getActiveRequestNo())) {
                throw new AiConversationConflictException(
                        AiConversationConflictException.AI_REQUEST_IN_PROGRESS,
                        "请求不是当前活动请求");
            }
            PassengerAiMessage userMessage = messages.findUserByRequestNo(conversation.getId(), requestNo);
            if (userMessage == null) {
                throw new AiConversationConflictException(
                        AiConversationConflictException.AI_REQUEST_IN_PROGRESS,
                        "活动请求缺少用户消息");
            }
            long sequence = conversation.getLastMessageSequence() + 1;
            LocalDateTime now = PassengerPersistenceTime.now();
            PassengerAiMessage failedMessage = new PassengerAiMessage()
                    .setMessageNo(identifiers.nextMessageNo())
                    .setConversationId(conversation.getId())
                    .setSequenceNo(sequence)
                    .setRequestNo(requestNo)
                    .setReplyToMessageId(userMessage.getId())
                    .setRole(ROLE_ASSISTANT)
                    .setMessageType(TYPE_TEXT)
                    .setContent(FAIL_CONTENT)
                    .setStatus(STATUS_FAILED)
                    .setFailureCode(failureCode)
                    .setFailureMessage(failureMessage)
                    .setCompletedAt(now)
                    .setCreatedAt(now)
                    .setUpdatedAt(now);
            messages.insert(failedMessage);

            int released = conversations.releaseActiveRequest(conversation.getId(), requestNo, sequence, now);
            if (released != 1) {
                throw new AiConversationConflictException(
                        AiConversationConflictException.AI_REQUEST_IN_PROGRESS,
                        "活动请求释放失败");
            }
            return new AiTurnFailResponse(failedMessage.getMessageNo(), requestNo, sequence);
        });
    }

    /**
     * 分页读取历史消息：不带 beforeSequence 返回最新一页，携带则向前翻更早消息（聊天标准语义），
     * 页内按会话内序号升序。归属与可用性校验走会话行；已删除会话不可查看（按不存在处理）。
     */
    public AiMessageListResponse listMessages(String conversationNo, long customerId,
                                              Integer limit, Long beforeSequence) {
        PassengerAiConversation conversation = requireConversation(conversationNo, customerId);
        requireAvailable(conversation);
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        long before = beforeSequence == null || beforeSequence <= 0 ? Long.MAX_VALUE : beforeSequence;

        // 多取一条判断是否还有更早消息；mapper 按序号倒序取，这里反转成正序展示。
        List<PassengerAiMessage> page = messages.findByConversationRange(
                conversation.getId(), before, pageSize + 1);
        boolean hasMore = page.size() > pageSize;
        if (hasMore) {
            page = page.subList(0, pageSize);
        }
        Collections.reverse(page);
        List<AiMessageListResponse.MessageView> views = page.stream()
                .map(message -> new AiMessageListResponse.MessageView(
                        message.getMessageNo(), message.getRequestNo(),
                        ROLE_USER.equals(message.getRole()) ? message.getClientMessageNo() : null,
                        message.getRole(),
                        message.getMessageType(), message.getContent(), message.getPayloadJson(),
                        message.getSequenceNo(), message.getStatus(), message.getCreatedAt()))
                .toList();
        return new AiMessageListResponse(views, hasMore);
    }

    private AiTurnBeginResponse replayResponse(PassengerAiConversation snapshot, PassengerAiMessage replayed) {
        PassengerAiMessage assistantReply = messages.findAssistantByRequestNo(snapshot.getId(), replayed.getRequestNo());
        return new AiTurnBeginResponse(replayed.getRequestNo(), replayed.getSequenceNo(),
                assistantView(messages.findLatestByRole(snapshot.getId(), ROLE_ASSISTANT)),
                assistantView(assistantReply));
    }

    private PassengerAiConversation requireConversation(String conversationNo, long customerId) {
        PassengerAiConversation conversation = conversations.findByConversationNo(conversationNo, customerId);
        if (conversation == null) {
            throw new AiConversationNotFoundException();
        }
        return conversation;
    }

    private PassengerAiConversation requireConversationForUpdate(String conversationNo, long customerId) {
        PassengerAiConversation conversation = conversations.findByConversationNoForUpdate(
                conversationNo, customerId);
        if (conversation == null) {
            throw new AiConversationNotFoundException();
        }
        return conversation;
    }

    private void requireAvailable(PassengerAiConversation conversation) {
        if (!STATUS_ACTIVE.equals(conversation.getStatus()) || conversation.getDeletedAt() != null) {
            throw new AiConversationNotFoundException();
        }
    }

    private boolean isStale(PassengerAiConversation conversation) {
        LocalDateTime startedAt = conversation.getActiveRequestStartedAt();
        // 历史脏数据若缺少开始时间，也不能让活动请求永久占住会话。
        return startedAt == null
                || startedAt.plusSeconds(staleTurnSeconds).isBefore(PassengerPersistenceTime.now());
    }

    private static AssistantMessageView assistantView(PassengerAiMessage message) {
        if (message == null) {
            return null;
        }
        return new AssistantMessageView(message.getMessageNo(), message.getRequestNo(), message.getRole(),
                message.getMessageType(), message.getContent(), message.getPayloadJson(),
                message.getSequenceNo(), message.getCreatedAt(), message.getStatus(),
                message.getFailureCode(), message.getFailureMessage());
    }

    private static AiTurnCompleteResponse completeView(PassengerAiMessage message) {
        Objects.requireNonNull(message, "completed message must not be null");
        if (!STATUS_COMPLETED.equals(message.getStatus())) {
            throw new AiConversationConflictException(STALE_REQUEST_CODE, "请求已经失败，不能写入迟到结果");
        }
        return new AiTurnCompleteResponse(message.getMessageNo(), message.getRequestNo(),
                message.getSequenceNo(), message.getCreatedAt());
    }
}
