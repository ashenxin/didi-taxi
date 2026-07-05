package com.sx.passengerapi.client.dto;

public class AppSettingsCustomerIdRequest {
    private Long customerId;

    public AppSettingsCustomerIdRequest() {
    }

    public AppSettingsCustomerIdRequest(Long customerId) {
        this.customerId = customerId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }
}
