package com.sx.passengerapi.client.dto;

public class AppPhoneChangeSmsSendRequest {
    private Long customerId;
    private String newPhone;

    public AppPhoneChangeSmsSendRequest() {
    }

    public AppPhoneChangeSmsSendRequest(Long customerId, String newPhone) {
        this.customerId = customerId;
        this.newPhone = newPhone;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public String getNewPhone() {
        return newPhone;
    }

    public void setNewPhone(String newPhone) {
        this.newPhone = newPhone;
    }
}
