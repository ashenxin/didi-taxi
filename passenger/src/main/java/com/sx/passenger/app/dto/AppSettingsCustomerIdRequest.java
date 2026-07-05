package com.sx.passenger.app.dto;

import jakarta.validation.constraints.NotNull;

public class AppSettingsCustomerIdRequest {
    @NotNull(message = "乘客ID不能为空")
    private Long customerId;

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }
}
