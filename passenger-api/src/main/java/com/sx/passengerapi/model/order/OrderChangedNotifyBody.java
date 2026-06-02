package com.sx.passengerapi.model.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class OrderChangedNotifyBody {

    @NotNull(message = "passengerId不能为空")
    private Long passengerId;

    @NotBlank(message = "orderNo不能为空")
    private String orderNo;

    public Long getPassengerId() {
        return passengerId;
    }

    public void setPassengerId(Long passengerId) {
        this.passengerId = passengerId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }
}
