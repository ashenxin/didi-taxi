package com.sx.order.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 网约车订单主表。
 * <p>对应 MySQL 库 {@code order}、表 {@code trip_order}。</p>
 * <p>状态机：{@code 0}CREATED → {@code 1}ASSIGNED → {@code 2}ACCEPTED → {@code 3}ARRIVED → {@code 4}STARTED → {@code 5}FINISHED；{@code 6}CANCELLED。</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("trip_order")
public class TripOrder {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务订单号（唯一） */
    private String orderNo;

    /** 乘客 ID，对应 passenger 库 {@code customer.id} */
    private Long passengerId;
    /** 指派司机 ID，对应 capacity 库 {@code driver.id} */
    private Long driverId;
    /** 服务车辆 ID，对应 capacity 库 {@code car.id} */
    private Long carId;
    /** 承运公司 ID，对应 capacity 库 {@code company.id} */
    private Long companyId;

    /** 产品线/车型编码（与计费、运力侧一致，如 ECONOMY） */
    private String productCode;
    /** 省份行政区划编码 */
    private String provinceCode;
    /** 城市行政区划编码 */
    private String cityCode;

    /** 起点地址文案 */
    private String originAddress;
    /** 起点纬度 */
    private BigDecimal originLat;
    /** 起点经度 */
    private BigDecimal originLng;

    /** 终点地址文案 */
    private String destAddress;
    /** 终点纬度 */
    private BigDecimal destLat;
    /** 终点经度 */
    private BigDecimal destLng;

    /**
     * 订单状态：0 已创建，1 已分配，2 已接单，3 司机已到达，4 行程中，5 已完成，6 已取消。
     */
    private Integer status;

    /** 下单时预估金额（计费 estimate 结果落库） */
    private BigDecimal estimatedAmount;
    /** 完单后实收/结算金额 */
    private BigDecimal finalAmount;

    /** 下单时选用的计价规则 ID，对应 calculate 库 {@code fare_rule.id} */
    private Long fareRuleId;
    /** 计价规则快照（MySQL JSON，实体用字符串读写，便于对账） */
    private String fareRuleSnapshot;

    /** 取消方：如 1 表示乘客取消（与订单服务内 {@code CANCEL_BY_PASSENGER} 常量一致） */
    private Integer cancelBy;
    /** 取消原因说明 */
    private String cancelReason;

    private LocalDateTime createdAt;
    /** 系统指派司机时间 */
    private LocalDateTime assignedAt;
    /** 司机确认接单时间 */
    private LocalDateTime acceptedAt;
    /** 司机到达上车点时间 */
    private LocalDateTime arrivedAt;
    /** 行程开始时间 */
    private LocalDateTime startedAt;
    /** 行程结束/完单时间 */
    private LocalDateTime finishedAt;
    /** 取消时间 */
    private LocalDateTime cancelledAt;
    private LocalDateTime updatedAt;

    /** 逻辑删除：0 未删除 */
    private Integer isDeleted;
}
