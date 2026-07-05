package com.sx.passengerapi.client.dto;

public class AppAccountCancelConfirmRequest {
    private Long customerId;
    private String code;
    private Boolean confirm;

    public AppAccountCancelConfirmRequest() {
    }

    public AppAccountCancelConfirmRequest(Long customerId, String code, Boolean confirm) {
        this.customerId = customerId;
        this.code = code;
        this.confirm = confirm;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Boolean getConfirm() {
        return confirm;
    }

    public void setConfirm(Boolean confirm) {
        this.confirm = confirm;
    }
}
