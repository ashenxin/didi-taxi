package com.sx.passenger.ai.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.passenger.ai.entity.PassengerAiMessage;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** AI 客服消息表 DAO；手写方法覆盖幂等、轮次关联与历史分页查询。 */
public interface PassengerAiMessageMapper extends BaseMapper<PassengerAiMessage> {

    /** 按客户端幂等键查用户消息，(conversation_id, client_message_no) 唯一索引落点。 */
    PassengerAiMessage findUserByClientMessageNo(@Param("conversationId") long conversationId,
                                                 @Param("clientMessageNo") String clientMessageNo);

    /** 最新一条指定角色消息，用于编排判断最新确认提问。 */
    PassengerAiMessage findLatestByRole(@Param("conversationId") long conversationId,
                                        @Param("role") String role);

    /** 本轮用户消息。 */
    PassengerAiMessage findUserByRequestNo(@Param("conversationId") long conversationId,
                                          @Param("requestNo") String requestNo);

    /** 本轮已持久化的客服回复，用于 complete/fail 幂等重放。 */
    PassengerAiMessage findAssistantByRequestNo(@Param("conversationId") long conversationId,
                                                @Param("requestNo") String requestNo);

    /** 历史分页：取 beforeSequence 之前（不含）按序号倒序的前 limit 条，由服务层反转成正序。 */
    List<PassengerAiMessage> findByConversationRange(@Param("conversationId") long conversationId,
                                                     @Param("beforeSequence") long beforeSequence,
                                                     @Param("limit") int limit);
}
