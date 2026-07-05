package com.sx.passengerapi.client.dto;

public class AppPhoneChangeConfirmRequest {
    private Long customerId;
    private String newPhone;
    private String code;

    public AppPhoneChangeConfirmRequest() {
    }

    public AppPhoneChangeConfirmRequest(Long customerId, String newPhone, String code) {
        this.customerId = customerId;
        this.newPhone = newPhone;
        this.code = code;
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
