package com.sx.calculate.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计价规则实体（按省/市/产品线维度配置，支持生效区间）。
 * 对应 MySQL 库 {@code calculate}、表 {@code fare_rule}。
 * 计费公式概要：起步价 + 超包含里程×单价 + 超包含时长×单价，再应用可选 {@code minimum_fare}/{@code maximum_fare}。
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("fare_rule")
public class FareRule {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 省份编码
     */
    @NotBlank(message = "省份编码不能为空")
    private String provinceCode;

    /**
     * 城市编码
     */
    @NotBlank(message = "城市编码不能为空")
    private String cityCode;

    /**
     * 产品线/车型档编码，与订单、运力侧约定一致
     */
    @NotBlank(message = "产品线编码不能为空")
    private String productCode;

    /**
     * 规则名称，便于运营识别
     */
    private String ruleName;

    /**
     * 生效开始时间
     */
    @NotNull(message = "生效开始时间不能为空")
    private LocalDateTime effectiveFrom;

    /**
     * 生效结束时间；为空表示当前仍有效
     */
    private LocalDateTime effectiveTo;

    /**
     * 起步价
     */
    @NotNull(message = "起步价不能为空")
    private BigDecimal baseFare;

    /**
     * 起步含里程（公里）
     */
    @NotNull(message = "起步含里程不能为空")
    private BigDecimal includedDistanceKm;

    /**
     * 起步含时长（分钟）
     */
    @NotNull(message = "起步含时长不能为空")
    private Integer includedDurationMin;

    /**
     * 超出后每公里单价
     */
    @NotNull(message = "超出后每公里单价不能为空")
    private BigDecimal perKmPrice;

    /**
     * 超出后每分钟单价
     */
    @NotNull(message = "超出后每分钟单价不能为空")
    private BigDecimal perMinutePrice;

    /**
     * 最低消费；为空表示不启用
     */
    private BigDecimal minimumFare;

    /**
     * 封顶价；为空表示不启用
     */
    private BigDecimal maximumFare;

    /**
     * 逻辑删除：0 未删除，非 0 已删除；库默认 0，入参可不传，由服务或库填充
     */
    private Integer isDeleted;

    /**
     * 创建时间，通常由库填充
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间，通常由库填充
     */
    private LocalDateTime updatedAt;
}
