package com.sx.order.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 司机拒单（未接单阶段）：{@code ASSIGNED / PENDING_DRIVER_CONFIRM → CREATED}，进入重新派单。
 */
public class DriverRejectBody {

    @NotNull(message = "driverId不能为空")
    private Long driverId;

    @NotBlank(message = "reasonCode不能为空")
    private String reasonCode;

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }
}
