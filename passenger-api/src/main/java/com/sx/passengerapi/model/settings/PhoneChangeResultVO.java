package com.sx.passengerapi.model.settings;

public class PhoneChangeResultVO {
    private Boolean changed;
    private Boolean requireLogin;
    private String maskedNewPhone;
    private String operationNo;
    private String status;

    public Boolean getChanged() {
        return changed;
    }

    public void setChanged(Boolean changed) {
        this.changed = changed;
    }

    public Boolean getRequireLogin() {
        return requireLogin;
    }

    public void setRequireLogin(Boolean requireLogin) {
        this.requireLogin = requireLogin;
    }

    public String getMaskedNewPhone() {
        return maskedNewPhone;
    }

    public void setMaskedNewPhone(String maskedNewPhone) {
        this.maskedNewPhone = maskedNewPhone;
    }

    public String getOperationNo() {
        return operationNo;
    }

    public void setOperationNo(String operationNo) {
        this.operationNo = operationNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
