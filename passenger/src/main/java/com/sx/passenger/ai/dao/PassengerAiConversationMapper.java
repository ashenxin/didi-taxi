package com.sx.passenger.ai.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.passenger.ai.entity.PassengerAiConversation;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** AI 客服会话表 DAO；手写方法覆盖加锁读取与活动请求的 CAS 占用/释放。 */
public interface PassengerAiConversationMapper extends BaseMapper<PassengerAiConversation> {

    /** 非锁定读取，用于事务外的幂等重放判断；归属校验并入 WHERE。 */
    PassengerAiConversation findByConversationNo(@Param("conversationNo") String conversationNo,
                                                 @Param("customerId") long customerId);

    /** 加锁读取；归属校验并入 WHERE，跨乘客访问按不存在处理。 */
    PassengerAiConversation findByConversationNoForUpdate(@Param("conversationNo") String conversationNo,
                                                          @Param("customerId") long customerId);

    /** 创建会话幂等重放查询。 */
    PassengerAiConversation findByCreateIdempotency(@Param("customerId") long customerId,
                                                    @Param("key") String key);

    /** 事务内占用活动请求并推进消息序号；row_version 双条件兜底，锁下必成功。 */
    int advanceTurn(@Param("id") long id,
                    @Param("expectedRowVersion") long expectedRowVersion,
                    @Param("requestNo") String requestNo,
                    @Param("sequence") long sequence,
                    @Param("startedAt") LocalDateTime startedAt,
                    @Param("lastMessageAt") LocalDateTime lastMessageAt);

    /** 事务内释放活动请求并推进消息序号。 */
    int releaseActiveRequest(@Param("id") long id,
                             @Param("requestNo") String requestNo,
                             @Param("sequence") long sequence,
                             @Param("lastMessageAt") LocalDateTime lastMessageAt);

    /** 为旧接管流程留下的孤儿请求补写失败消息，不改变当前活动请求。 */
    int advanceMessageSequence(@Param("id") long id,
                               @Param("expectedRowVersion") long expectedRowVersion,
                               @Param("sequence") long sequence,
                               @Param("lastMessageAt") LocalDateTime lastMessageAt);
}
