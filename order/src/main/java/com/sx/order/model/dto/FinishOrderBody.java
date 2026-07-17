package com.sx.order.model.dto;

import java.math.BigDecimal;

public class FinishOrderBody {

    private Long driverId;

    /** @deprecated 仅兼容旧客户端，结算不会读取。 */
    @Deprecated
    private BigDecimal distanceKm;

    /** @deprecated 仅兼容旧客户端，结算不会读取。 */
    @Deprecated
    private Integer durationMin;

    /** @deprecated 仅兼容旧客户端，结算不会读取。 */
    @Deprecated
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
