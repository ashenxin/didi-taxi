package com.sx.passenger.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 乘客端 AI 客服会话主记录。
 *
 * 字段与 {@code passenger_ai_conversation_patch.sql} 对齐；last_message_sequence 是会话内
 * 消息序号分配依据，active_request_no 表示当前占用会话的活动请求。时间统一使用
 * {@code PassengerPersistenceTime} 的 Asia/Shanghai LocalDateTime。
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("passenger_ai_conversation")
public class PassengerAiConversation {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外稳定会话编号，服务端生成 */
    private String conversationNo;

    /** 乘客ID；所有读写必须校验当前认证乘客 */
    private Long customerId;

    /** 创建会话幂等键 */
    private String createIdempotencyKey;

    /** 会话场景，本期恒为 ROUTE_WAYPOINT */
    private String sceneCode;

    /** 会话列表标题 */
    private String title;

    /** ACTIVE / DELETED */
    private String status;

    /** 会话内已分配的最大消息序号 */
    private Long lastMessageSequence;

    /** 供模型读取的派生摘要，不代替完整历史 */
    private String memorySummary;

    /** memory_summary 已覆盖到的消息序号 */
    private Long summaryThroughSequence;

    /** 会话并发更新 CAS 版本 */
    private Long rowVersion;

    /** 当前活动请求编号；完成、失败或超时接管后清空 */
    private String activeRequestNo;

    /** 当前活动请求开始时间，用于判断失联请求 */
    private LocalDateTime activeRequestStartedAt;

    /** 最近一条消息时间 */
    private LocalDateTime lastMessageAt;

    /** 删除原因：USER_DELETE / ACCOUNT_CANCEL */
    private String deleteReason;

    /** 账号注销触发删除时关联的生命周期操作号 */
    private String deleteOperationNo;

    /** 逻辑删除时间；非空后乘客端不得再查看 */
    private LocalDateTime deletedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
