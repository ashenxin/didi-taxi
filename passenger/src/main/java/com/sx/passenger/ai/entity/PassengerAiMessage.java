package com.sx.passenger.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * AI 客服会话消息，用户消息与客服回复按顺序共用同一会话序号序列。
 *
 * 每一轮共用同一个 request_no，客服回复通过 reply_to_message_id 指向该轮用户消息；
 * client_message_no 只存在于用户消息，是 (conversation_id, client_message_no) 幂等落点。
 * payload_json 只保存展示摘要，完整路线折线等地图事实留在 map-service 短时缓存。
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("passenger_ai_message")
public class PassengerAiMessage {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 服务端生成的消息编号 */
    private String messageNo;

    /** 所属会话 id */
    private Long conversationId;

    /** 会话内消息序号，由会话主表分配 */
    private Long sequenceNo;

    /** 本轮请求号；用户消息与对应客服回复相同 */
    private String requestNo;

    /** 客户端幂等键，仅用户消息 */
    private String clientMessageNo;

    /** AI 回复指向的用户消息 id */
    private Long replyToMessageId;

    /** USER / ASSISTANT */
    private String role;

    /** TEXT / ROUTE_CARD 等 */
    private String messageType;

    /** 消息正文 */
    private String content;

    /** 展示摘要 JSON，不含完整折线 */
    private String payloadJson;

    /** GENERATING / COMPLETED / FAILED */
    private String status;

    private String modelProvider;

    private String modelName;

    private Integer promptTokens;

    private Integer completionTokens;

    private String failureCode;

    private String failureMessage;

    private LocalDateTime completedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
