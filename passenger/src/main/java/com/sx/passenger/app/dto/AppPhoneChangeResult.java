package com.sx.passenger.app.dto;

public class AppPhoneChangeResult {
    private Boolean changed;
    private Boolean requireLogin;
    private String maskedNewPhone;

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
}
