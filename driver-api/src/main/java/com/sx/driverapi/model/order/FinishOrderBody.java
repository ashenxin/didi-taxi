package com.sx.driverapi.model.order;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class FinishOrderBody {

    @NotNull(message = "driverId不能为空")
    private Long driverId;

    private BigDecimal distanceKm;
    private Integer durationMin;
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
