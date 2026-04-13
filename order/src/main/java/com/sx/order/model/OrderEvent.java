package com.sx.order.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 订单状态变更与业务事件流水（审计/补偿用）。
 * 对应 MySQL 库 {@code order}、表 {@code order_event}。
 * 与 {@link TripOrder} 通过 {@code order_id} / {@code order_no} 关联。
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("order_event")
public class OrderEvent {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联订单主键，对应表 {@code trip_order} 主键 */
    private Long orderId;
    /** 冗余订单号，便于按单号查询时间线 */
    private String orderNo;

    /**
     * 事件类型英文常量，如 ORDER_CREATED、ORDER_ASSIGNED、ORDER_ACCEPTED、ORDER_CANCELLED 等。
     */
    private String eventType;
    /** 变更前 {@link TripOrder#getStatus()}，创建事件可为 null */
    private Integer fromStatus;
    /** 变更后 {@link TripOrder#getStatus()} */
    private Integer toStatus;

    /**
     * 操作人类型：0 系统，1 乘客，2 司机（与业务常量一致）。
     */
    private Integer operatorType;
    /** 操作人业务 ID（乘客/司机 ID 等），系统事件可为 null */
    private Long operatorId;

    /** 原因分类码（可选） */
    private String reasonCode;
    /** 原因描述（如取消文案、审核说明） */
    private String reasonDesc;

    /** 扩展载荷（MySQL JSON，实体用字符串读写） */
    private String eventPayload;

    /** 业务发生时刻（展示时间线用） */
    private LocalDateTime occurredAt;
    /** 落库时间 */
    private LocalDateTime createdAt;
}
