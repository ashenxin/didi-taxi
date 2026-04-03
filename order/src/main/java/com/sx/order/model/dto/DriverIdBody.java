package com.sx.order.model.dto;

import jakarta.validation.constraints.NotNull;

public class DriverIdBody {

    @NotNull(message = "driverId不能为空")
    private Long driverId;

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }
}
