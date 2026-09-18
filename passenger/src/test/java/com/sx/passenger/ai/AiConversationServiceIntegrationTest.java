package com.sx.passenger.ai;

import com.sx.passenger.ai.dto.AiConversationCreateResponse;
import com.sx.passenger.ai.dto.AiMessageListResponse;
import com.sx.passenger.ai.dto.AiTurnBeginResponse;
import com.sx.passenger.ai.dto.AiTurnCompleteResponse;
import com.sx.passenger.ai.dto.AiTurnFailResponse;
import com.sx.passenger.ai.exception.AiConversationConflictException;
import com.sx.passenger.ai.exception.AiConversationNotFoundException;
import com.sx.passenger.ai.service.AiConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AiConversationServiceIntegrationTest {

    private static final long CUSTOMER_ID = 26_001L;
    private static final long OTHER_CUSTOMER_ID = 26_002L;

    @Autowired
    AiConversationService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanRows() {
        jdbc.update("DELETE FROM passenger_ai_message");
        jdbc.update("DELETE FROM passenger_ai_conversation");
    }

    @Test
    void createAssignsConversationNoAndReplaysOnSameIdempotencyKey() {
        AiConversationCreateResponse first = service.create(CUSTOMER_ID, "create-1");
        AiConversationCreateResponse replay = service.create(CUSTOMER_ID, "create-1");

        assertThat(first.conversationNo()).startsWith("AIC");
        assertThat(replay.conversationNo()).isEqualTo(first.conversationNo());
        assertThat(conversationCount()).isEqualTo(1);
    }

    @Test
    void createWithDifferentKeysCreatesDistinctConversations() {
        String a = service.create(CUSTOMER_ID, "create-a").conversationNo();
        String b = service.create(CUSTOMER_ID, "create-b").conversationNo();

        assertThat(a).isNotEqualTo(b);
        assertThat(conversationCount()).isEqualTo(2);
    }

    @Test
    void beginAllocatesSequenceAndOccupiesActiveRequest() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();

        AiTurnBeginResponse begin = service.begin(conversationNo, CUSTOMER_ID, "turn-1", "帮我规划一条路线");

        assertThat(begin.requestNo()).startsWith("REQ");
        assertThat(begin.requestVersion()).isEqualTo(1);
        assertThat(begin.assistantReply()).isNull();
        Map<String, Object> conversation = conversationRow(conversationNo);
        assertThat(conversation.get("ACTIVE_REQUEST_NO")).isEqualTo(begin.requestNo());
        assertThat(((Number) conversation.get("LAST_MESSAGE_SEQUENCE")).longValue()).isEqualTo(1);
        assertThat(messageCount(conversationNo)).isEqualTo(1);
    }

    @Test
    void beginWhileActiveRequestOccupiedConflictsForDifferentKey() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        service.begin(conversationNo, CUSTOMER_ID, "turn-1", "第一条消息");

        assertThatThrownBy(() -> service.begin(conversationNo, CUSTOMER_ID, "turn-2", "第二条消息"))
                .isInstanceOf(AiConversationConflictException.class)
                .satisfies(e -> assertThat(((AiConversationConflictException) e).getCode())
                        .isEqualTo(AiConversationConflictException.AI_REQUEST_IN_PROGRESS));
    }

    @Test
    void beginSameKeyWhileInProgressConflictsWithProcessing() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        service.begin(conversationNo, CUSTOMER_ID, "turn-1", "第一条消息");

        assertThatThrownBy(() -> service.begin(conversationNo, CUSTOMER_ID, "turn-1", "第一条消息"))
                .isInstanceOf(AiConversationConflictException.class)
                .satisfies(e -> assertThat(((AiConversationConflictException) e).getCode())
                        .isEqualTo(AiConversationConflictException.AI_REQUEST_PROCESSING));
    }

    @Test
    void concurrentBeginWithSameKeyCreatesOneUserMessage() throws Exception {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> beginAfterSignal(start, conversationNo));
            Future<Object> second = executor.submit(() -> beginAfterSignal(start, conversationNo));
            start.countDown();
            Object a = first.get(10, TimeUnit.SECONDS);
            Object b = second.get(10, TimeUnit.SECONDS);

            assertThat(java.util.List.of(a, b).stream().filter(AiTurnBeginResponse.class::isInstance).count())
                    .isEqualTo(1);
            assertThat(java.util.List.of(a, b).stream().filter(AiConversationConflictException.class::isInstance)
                    .map(AiConversationConflictException.class::cast).map(AiConversationConflictException::getCode))
                    .containsExactly(AiConversationConflictException.AI_REQUEST_PROCESSING);
            assertThat(messageCount(conversationNo)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private Object beginAfterSignal(CountDownLatch start, String conversationNo) throws InterruptedException {
        start.await();
        try {
            return service.begin(conversationNo, CUSTOMER_ID, "same-key", "同一条消息");
        } catch (AiConversationConflictException e) {
            return e;
        }
    }

    @Test
    void beginSameKeyWithDifferentContentConflicts() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        service.begin(conversationNo, CUSTOMER_ID, "turn-1", "第一条消息");
        service.complete(conversationNo, activeRequestNo(conversationNo), CUSTOMER_ID,
                "TEXT", "已收到", null);

        assertThatThrownBy(() -> service.begin(conversationNo, CUSTOMER_ID, "turn-1", "内容变了"))
                .isInstanceOf(AiConversationConflictException.class)
                .satisfies(e -> assertThat(((AiConversationConflictException) e).getCode())
                        .isEqualTo(AiConversationConflictException.AI_IDEMPOTENCY_CONFLICT));
    }

    @Test
    void beginTakesOverStaleActiveRequest() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        Long conversationId = conversationId(conversationNo);
        LocalDateTime staleStartedAt = LocalDateTime.now().minusMinutes(2);
        jdbc.update("UPDATE passenger_ai_conversation SET active_request_no = ?, active_request_started_at = ?, "
                        + "last_message_sequence = 1, row_version = 1 WHERE id = ?",
                "REQ-stale-old-request", Timestamp.valueOf(staleStartedAt), conversationId);
        jdbc.update("INSERT INTO passenger_ai_message (message_no, conversation_id, sequence_no,"
                        + " request_no, client_message_no, role, message_type, content, status, completed_at,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'USER', 'TEXT', ?, 'COMPLETED', ?, ?, ?)",
                "AIM-stale-user", conversationId, 1L, "REQ-stale-old-request", "stale-key",
                "失联前的消息", Timestamp.valueOf(staleStartedAt), Timestamp.valueOf(staleStartedAt),
                Timestamp.valueOf(staleStartedAt));

        AiTurnBeginResponse begin = service.begin(conversationNo, CUSTOMER_ID, "turn-1", "接管后的消息");

        assertThat(begin.requestVersion()).isEqualTo(3);
        assertThat(messageCount(conversationNo)).isEqualTo(3);
        Map<String, Object> failed = singleMessage(conversationNo, "ASSISTANT");
        assertThat(failed.get("STATUS")).isEqualTo("FAILED");
        assertThat(failed.get("FAILURE_CODE")).isEqualTo("AI_REQUEST_EXPIRED");
        Map<String, Object> conversation = conversationRow(conversationNo);
        assertThat(conversation.get("ACTIVE_REQUEST_NO")).isEqualTo(begin.requestNo());
    }

    @Test
    void staleSameKeyReplaysStoredFailureAndLateCompletionCannotOverwriteIt() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        AiTurnBeginResponse first = service.begin(conversationNo, CUSTOMER_ID, "turn-1", "旧消息");
        jdbc.update("UPDATE passenger_ai_conversation SET active_request_started_at = ? WHERE conversation_no = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(2)), conversationNo);

        AiTurnBeginResponse replay = service.begin(conversationNo, CUSTOMER_ID, "turn-1", "旧消息");

        assertThat(replay.requestNo()).isEqualTo(first.requestNo());
        assertThat(replay.assistantReply().status()).isEqualTo("FAILED");
        assertThat(replay.assistantReply().failureCode()).isEqualTo("AI_REQUEST_EXPIRED");
        assertThat(conversationRow(conversationNo).get("ACTIVE_REQUEST_NO")).isNull();
        assertThat(messageCount(conversationNo)).isEqualTo(2);
        assertThatThrownBy(() -> service.complete(conversationNo, first.requestNo(), CUSTOMER_ID,
                "TEXT", "迟到的成功", null))
                .isInstanceOf(AiConversationConflictException.class)
                .satisfies(e -> assertThat(((AiConversationConflictException) e).getCode())
                        .isEqualTo("AI_REQUEST_EXPIRED"));
        assertThat(messageCount(conversationNo)).isEqualTo(2);
    }

    @Test
    void missingActiveStartTimeDoesNotBlockSameKeyForever() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        AiTurnBeginResponse first = service.begin(conversationNo, CUSTOMER_ID, "turn-1", "旧消息");
        jdbc.update("UPDATE passenger_ai_conversation SET active_request_started_at = NULL "
                + "WHERE conversation_no = ?", conversationNo);

        AiTurnBeginResponse replay = service.begin(conversationNo, CUSTOMER_ID, "turn-1", "旧消息");

        assertThat(replay.requestNo()).isEqualTo(first.requestNo());
        assertThat(replay.assistantReply().status()).isEqualTo("FAILED");
        assertThat(conversationRow(conversationNo).get("ACTIVE_REQUEST_NO")).isNull();
    }

    @Test
    void completePersistsAssistantMessageAndReleasesActiveRequest() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        AiTurnBeginResponse begin = service.begin(conversationNo, CUSTOMER_ID, "turn-1", "帮我规划一条路线");

        AiTurnCompleteResponse complete = service.complete(conversationNo, begin.requestNo(), CUSTOMER_ID,
                "TEXT", "我理解的起点是A，终点是B，对吗？", "{\"endpointConfirmation\":true}");

        assertThat(complete.sequenceNo()).isEqualTo(2);
        Map<String, Object> conversation = conversationRow(conversationNo);
        assertThat(conversation.get("ACTIVE_REQUEST_NO")).isNull();
        Map<String, Object> assistant = singleMessage(conversationNo, "ASSISTANT");
        assertThat(assistant.get("REQUEST_NO")).isEqualTo(begin.requestNo());
        assertThat(assistant.get("REPLY_TO_MESSAGE_ID"))
                .isEqualTo(singleMessage(conversationNo, "USER").get("ID"));
    }

    @Test
    void completeReplaysSameResultOnRetry() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        AiTurnBeginResponse begin = service.begin(conversationNo, CUSTOMER_ID, "turn-1", "帮我规划一条路线");
        AiTurnCompleteResponse first = service.complete(conversationNo, begin.requestNo(), CUSTOMER_ID,
                "TEXT", "我理解的起点是A，终点是B，对吗？", null);

        AiTurnCompleteResponse replay = service.complete(conversationNo, begin.requestNo(), CUSTOMER_ID,
                "TEXT", "我理解的起点是A，终点是B，对吗？", null);

        assertThat(replay.messageNo()).isEqualTo(first.messageNo());
        assertThat(messageCount(conversationNo)).isEqualTo(2);
    }

    @Test
    void completeWithWrongRequestNoConflicts() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        service.begin(conversationNo, CUSTOMER_ID, "turn-1", "帮我规划一条路线");

        assertThatThrownBy(() -> service.complete(conversationNo, "REQ-not-active", CUSTOMER_ID,
                "TEXT", "回复", null))
                .isInstanceOf(AiConversationConflictException.class);
    }

    @Test
    void beginReplayAfterCompletionReturnsAssistantReply() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        AiTurnBeginResponse begin = service.begin(conversationNo, CUSTOMER_ID, "turn-1", "帮我规划一条路线");
        service.complete(conversationNo, begin.requestNo(), CUSTOMER_ID, "TEXT", "我理解了，对吗？", null);

        AiTurnBeginResponse replay = service.begin(conversationNo, CUSTOMER_ID, "turn-1", "帮我规划一条路线");

        assertThat(replay.requestNo()).isEqualTo(begin.requestNo());
        assertThat(replay.assistantReply()).isNotNull();
        assertThat(replay.assistantReply().content()).isEqualTo("我理解了，对吗？");
        assertThat(replay.assistantReply().status()).isEqualTo("COMPLETED");
        assertThat(messageCount(conversationNo)).isEqualTo(2);
    }

    @Test
    void failPersistsFailedMessageAndReleasesActiveRequest() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        AiTurnBeginResponse begin = service.begin(conversationNo, CUSTOMER_ID, "turn-1", "帮我规划一条路线");

        AiTurnFailResponse fail = service.fail(conversationNo, begin.requestNo(), CUSTOMER_ID,
                "AI_MAP_TIMEOUT", "地图服务超时");

        assertThat(fail.sequenceNo()).isEqualTo(2);
        Map<String, Object> failed = singleMessage(conversationNo, "ASSISTANT");
        assertThat(failed.get("STATUS")).isEqualTo("FAILED");
        assertThat(failed.get("FAILURE_CODE")).isEqualTo("AI_MAP_TIMEOUT");
        assertThat(conversationRow(conversationNo).get("ACTIVE_REQUEST_NO")).isNull();
    }

    @Test
    void beginReplayAfterFailureReturnsStoredFailureDetails() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        AiTurnBeginResponse begin = service.begin(conversationNo, CUSTOMER_ID, "turn-1", "从A到B");
        service.fail(conversationNo, begin.requestNo(), CUSTOMER_ID,
                "AI_PROVIDER_UNAVAILABLE", "客服暂时没能处理这条消息，请稍后重试");

        AiTurnBeginResponse replay = service.begin(conversationNo, CUSTOMER_ID, "turn-1", "从A到B");

        assertThat(replay.requestNo()).isEqualTo(begin.requestNo());
        assertThat(replay.assistantReply().status()).isEqualTo("FAILED");
        assertThat(replay.assistantReply().failureCode()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
        assertThat(replay.assistantReply().failureMessage())
                .isEqualTo("客服暂时没能处理这条消息，请稍后重试");
        assertThat(messageCount(conversationNo)).isEqualTo(2);
    }

    @Test
    void missingOrForeignConversationThrowsNotFound() {
        service.create(CUSTOMER_ID, "create-1");

        assertThatThrownBy(() -> service.begin("AIC-nonexistent", CUSTOMER_ID, "turn-1", "内容"))
                .isInstanceOf(AiConversationNotFoundException.class);

        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        assertThatThrownBy(() -> service.begin(conversationNo, OTHER_CUSTOMER_ID, "turn-1", "内容"))
                .isInstanceOf(AiConversationNotFoundException.class);
    }

    @Test
    void deletedConversationThrowsNotFound() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        jdbc.update("UPDATE passenger_ai_conversation SET status = 'DELETED', deleted_at = ? WHERE conversation_no = ?",
                Timestamp.valueOf(LocalDateTime.now()), conversationNo);

        assertThatThrownBy(() -> service.begin(conversationNo, CUSTOMER_ID, "turn-1", "内容"))
                .isInstanceOf(AiConversationNotFoundException.class);
    }

    @Test
    void listMessagesReturnsAscendingSequenceOrder() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        AiTurnBeginResponse begin = service.begin(conversationNo, CUSTOMER_ID, "turn-1", "第一条");
        service.complete(conversationNo, begin.requestNo(), CUSTOMER_ID, "TEXT", "回复一", null);

        AiMessageListResponse result = service.listMessages(conversationNo, CUSTOMER_ID, null, null);

        assertThat(result.hasMore()).isFalse();
        assertThat(result.messages()).hasSize(2);
        assertThat(result.messages().get(0).role()).isEqualTo("USER");
        assertThat(result.messages().get(0).clientMessageNo()).isEqualTo("turn-1");
        assertThat(result.messages().get(0).sequenceNo()).isEqualTo(1);
        assertThat(result.messages().get(1).role()).isEqualTo("ASSISTANT");
        assertThat(result.messages().get(1).clientMessageNo()).isNull();
        assertThat(result.messages().get(1).sequenceNo()).isEqualTo(2);
        assertThat(result.messages().get(1).content()).isEqualTo("回复一");
    }

    @Test
    void listMessagesPaginatesByBeforeSequence() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();
        for (int i = 1; i <= 2; i++) {
            AiTurnBeginResponse begin = service.begin(conversationNo, CUSTOMER_ID, "turn-" + i, "消息" + i);
            service.complete(conversationNo, begin.requestNo(), CUSTOMER_ID, "TEXT", "回复" + i, null);
        }

        // 不带 beforeSequence：返回最新一页（页内升序），hasMore 表示还有更早消息。
        AiMessageListResponse firstPage = service.listMessages(conversationNo, CUSTOMER_ID, 2, null);

        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.messages()).hasSize(2);
        assertThat(firstPage.messages().get(0).sequenceNo()).isEqualTo(3);
        assertThat(firstPage.messages().get(1).sequenceNo()).isEqualTo(4);

        // 带 beforeSequence=3：向前翻 sequence < 3 的更早消息。
        AiMessageListResponse older = service.listMessages(conversationNo, CUSTOMER_ID, 2, 3L);

        assertThat(older.hasMore()).isFalse();
        assertThat(older.messages()).hasSize(2);
        assertThat(older.messages().get(0).sequenceNo()).isEqualTo(1);
        assertThat(older.messages().get(1).sequenceNo()).isEqualTo(2);
    }

    @Test
    void listMessagesRejectsForeignCustomerAsNotFound() {
        String conversationNo = service.create(CUSTOMER_ID, "create-1").conversationNo();

        assertThatThrownBy(() -> service.listMessages(conversationNo, OTHER_CUSTOMER_ID, null, null))
                .isInstanceOf(AiConversationNotFoundException.class);
    }

    private long conversationCount() {
        return count("SELECT COUNT(*) FROM passenger_ai_conversation");
    }

    private long messageCount(String conversationNo) {
        return count("SELECT COUNT(*) FROM passenger_ai_message m"
                + " JOIN passenger_ai_conversation c ON m.conversation_id = c.id"
                + " WHERE c.conversation_no = ?", conversationNo);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private Long conversationId(String conversationNo) {
        return jdbc.queryForObject(
                "SELECT id FROM passenger_ai_conversation WHERE conversation_no = ?", Long.class, conversationNo);
    }

    private Map<String, Object> conversationRow(String conversationNo) {
        return jdbc.queryForMap("SELECT * FROM passenger_ai_conversation WHERE conversation_no = ?", conversationNo);
    }

    private Map<String, Object> singleMessage(String conversationNo, String role) {
        return jdbc.queryForMap("SELECT * FROM passenger_ai_message m"
                + " JOIN passenger_ai_conversation c ON m.conversation_id = c.id"
                + " WHERE c.conversation_no = ? AND m.role = ?", conversationNo, role);
    }

    private String activeRequestNo(String conversationNo) {
        return (String) conversationRow(conversationNo).get("ACTIVE_REQUEST_NO");
    }
}
