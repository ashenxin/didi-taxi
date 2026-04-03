package com.sx.passengerapi.model.order;

import jakarta.validation.constraints.NotNull;

public class CancelOrderRequest {

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
