package com.sx.order.model.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 乘客/系统取消订单入参。
 */
public class CancelOrderBody {

    @NotNull(message = "passengerId不能为空")
    private Long passengerId;

    private String cancelReason;

    public Long getPassengerId() {
        return passengerId;
    }

    public void setPassengerId(Long passengerId) {
        this.passengerId = passengerId;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }
}
