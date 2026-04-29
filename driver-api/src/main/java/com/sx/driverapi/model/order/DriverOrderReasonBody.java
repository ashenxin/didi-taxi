package com.sx.driverapi.model.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 司机拒单 / 司机取消（到达前）共用请求体：须与登录身份一致。
 */
public class DriverOrderReasonBody {

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
