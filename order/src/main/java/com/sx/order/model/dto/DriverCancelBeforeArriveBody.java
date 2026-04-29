package com.sx.order.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 司机取消（已接单、到达前）：{@code ACCEPTED → CREATED}，进入重新派单。
 */
public class DriverCancelBeforeArriveBody {

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
