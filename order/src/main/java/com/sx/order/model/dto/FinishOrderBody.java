package com.sx.order.model.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class FinishOrderBody {

    @NotNull(message = "driverId不能为空")
    private Long driverId;

    /** 实际里程（公里），MVP 可选，供后续结算 */
    private BigDecimal distanceKm;

    /** 实际时长（分钟），MVP 可选 */
    private Integer durationMin;

    /** 实付金额；为空时暂用订单 {@code estimated_amount} 作为兜底 */
    private BigDecimal finalAmount;

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public BigDecimal getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(BigDecimal distanceKm) {
        this.distanceKm = distanceKm;
    }

    public Integer getDurationMin() {
        return durationMin;
    }

    public void setDurationMin(Integer durationMin) {
        this.durationMin = durationMin;
    }

    public BigDecimal getFinalAmount() {
        return finalAmount;
    }

    public void setFinalAmount(BigDecimal finalAmount) {
        this.finalAmount = finalAmount;
    }
}
